package com.happysnaker.strategy.impl;

import com.happysnaker.config.OrderRabbitMqConfig;
import com.happysnaker.exception.OrderAddException;
import com.happysnaker.exception.ReadWriterLockException;
import com.happysnaker.pojo.Order;
import com.happysnaker.pojo.OrderMessage;
import com.happysnaker.service.OrderService;
import com.happysnaker.utils.VerifyUtils;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 乐观锁的架构，直接在数据库层面使用乐观锁进行扣减库存，适用于并发量较少的情况
 * @author Happysnaker
 * @description
 * @date 2022/3/15
 * @email happysnaker@foxmail.com
 */
@Component
@Transactional(rollbackFor = Exception.class)
@Configuration
@EnableRabbit
public class OptimisticPlaceOrderStrategy extends AbstractPlaceOrderStrategy {
    @Override
    public Map doPlaceOrder(Order order) throws ReadWriterLockException, OrderAddException {
        return addUserOrder(order);
    }


    /**
     * <p>这是原先的代码，减少了预减 redis 的逻辑，其余大体类似</p>
     * 添加订单要做的事有<br/>
     * <ul>
     *  <li> 计算订单ID<br/></li>
     *  <li>扣减库存，扣减库存必须保证库存充足<br/></li>
     * <li> 增加用户使用的折扣数目<br/></li>
     * <li>增加用户会员积分<br/></li>
     * <li> 扣减优惠券，目前不支持<br/></li>
     * <li>向 order 表 和 order_dish 表写订单数据<br/></li>
     *  <li>增加对应菜品销量<br/></li>
     * <li> 将菜品写入等待队列，以便计算新用户下单后需要等待的时常<br/></li>
     * </ul>
     */
    public Map addUserOrder(Order order) throws OrderAddException, ReadWriterLockException {
        if (order == null) {
            return null;
        }
        String userId = order.getUserId();

        if (!order.getIsNew()) {
            order.setIsNew(false);
            //这是一个已经在表中存在的订单，由用户继续加餐而来
            handleOldOrder(order);
            return null;
        }
        order.setIsNew(true);

        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        order.setCreateTime(timestamp);

        //生成订单编号
        String orderId = UUID.randomUUID().toString();


        order.setId(orderId);
        order.setUserId(userId);



        if (order.getConsumeType() == 2) {
            // 以当前时间戳生产取餐码，确保取餐吗唯一
            String code = VerifyUtils.BaseConversion(System.currentTimeMillis(), 32);
            order.setFetchMealCode(code);
        }


        //m 保存的是菜品ID与要下单的数量，主要是将套餐中的每个菜品与单点菜品合并
        Map<Integer, Integer> dishNumMap = getDishNumMap(order.getDishOrders());


        try {
            // 数据库层面会进行乐观锁判断
            // 订单算是生成成功，产生随机支付单号，发起支付
            order.setPayId(UUID.randomUUID().toString().replace("-", ""));
            OrderMessage om = new OrderMessage(dishNumMap, order);

            rabbit.convertAndSend(OrderRabbitMqConfig.ORDER_ADD_ROUTEING_KEY, om);

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        // 返回支付单号给前端
        Map map = new HashMap(2);
        map.put("payId", order.getPayId());
        map.put("orderId", order.getId());
        return map;
    }

    /**
     * 处理旧订单
     *
     * @param order
     * @throws OrderAddException
     */
    public void handleOldOrder(Order order) throws OrderAddException, ReadWriterLockException {
        // 筛选出添加的菜
        List<Map<String, Object>> dishOrders = order.getDishOrders().stream().filter((item -> {
            return (Boolean) item.getOrDefault("isAdd", false);
        })).collect(Collectors.toList());
        Map<Integer, Integer> dishNumMap = getDishNumMap(dishOrders);

        order.setDishOrders(dishOrders);

        // 扣减库存
        boolean hasDeduction = false;
        try {
            // 库存扣减成功，订单算完成，发布消息
            order.setOrderType(OrderService.CONFIRMING_STATUS);
            OrderMessage om = new OrderMessage(dishNumMap, order);

            rabbit.convertAndSend(OrderRabbitMqConfig.ORDER_ADD_ROUTEING_KEY, om);
        }  catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
}
