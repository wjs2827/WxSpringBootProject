package com.happysnaker.service.impl;

import com.happysnaker.config.OrderRabbitMqConfig;
import com.happysnaker.config.RedisCacheManager;
import com.happysnaker.exception.OrderAddException;
import com.happysnaker.pojo.Order;
import com.happysnaker.pojo.OrderMessage;
import com.happysnaker.service.BaseService;
import com.happysnaker.service.OrderConsumer;
import com.happysnaker.service.OrderService;
import com.happysnaker.utils.JsonUtils;
import com.happysnaker.utils.Pair;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 订单消费者
 *
 * @author Happysnaker
 * @description
 * @date 2022/3/14
 * @email happysnaker@foxmail.com
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Configuration
@EnableRabbit
public class OrderConsumerImpl extends BaseService implements OrderConsumer {

    @Autowired
    private OrderService service;

    @Qualifier("myRabbitTemplate")
    @Autowired
    RabbitTemplate rabbit;


    /**
     * 一次消费加多少积分
     */
    int integral = 5;


    /**
     * @param bytes   消息队列传递的序列化的消息，可调用{@link JsonUtils#getObjectFromBytes(byte[])} 方法转换成 {@link OrderMessage}
     * @param msg     消息队列中标识消息的属性
     * @param channel 消息队列中传递消息的通道
     * @see OrderConsumer#doAddOrder(byte[], Message, Channel)
     */
    @Override
    @RabbitListener(queues = {OrderRabbitMqConfig.ORDER_ADD_QUEUE})
    @Transactional(rollbackFor = Exception.class)
    public void doAddOrder(byte[] bytes, Message msg, Channel channel) throws Exception {
        channel.basicQos(1);
        OrderMessage om = null;
        boolean b = true;
        boolean present = false;
        System.out.println("收到消息，开始执行");
        try {
            om = (OrderMessage) JsonUtils.getObjectFromBytes(bytes);
            // 设置，防止重复消费
            present = redis.opsForValue().setIfAbsent(
                    RedisCacheManager.getOrderMessageCacheKey(om.getMessageId()), 0);
            if (!present) {
                System.out.println("om.getMessageId() = " + om.getMessageId());
                System.out.println("redisManager.hasKey( RedisCacheManager.getOrderMessageCacheKey(om.getMessageId())) = " + redisManager.hasKey(RedisCacheManager.getOrderMessageCacheKey(om.getMessageId())));
                System.out.println("重复消费123456!!");
                // setIfPresent 是原子操作，只有一个线程能设置成功
                // 一旦设置失败，说明已经有线程在消费了
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
        }
        // 再次判断，防止重复消费
        if (!present) {
            System.out.println("重复消费!!");
            return;
        }

        com.happysnaker.pojo.Order order = om.getOrder();
        System.out.println("消费！");
        try {
            // 先扣减库存，看看能不能通过乐观锁
            // 如果失败的话，会抛出异常，我们添加了 Transactional 注解，所有事务将回滚
            Map<Integer, Integer> dishNumMap = om.getDishNumMap();
            if (dishNumMap != null) {
                // 如果没扣减库存，那么消费者负责扣减库存
                System.out.println("扣库存");
                deductionInventory(dishNumMap, order.getStoreId());
            }
            addToWaitingQueue(dishNumMap, order.getStoreId());

            // 如果不是已经下单了的订单（继续加餐的订单），此时订单是新的，需要写入订单表
            if (order.getIsNew()) {
                orderMapper.insertOrderInfo(order);
                orderMapper.insertOrderPay(order.getId(), order.getPayId());
                // 新下单、增加积分
                userMapper.updateUserPoints(order.getUserId(), integral);
            } else {
                // 继续加餐的订单价格可能改变，更新价格，并且让管理员重新确认，更新订单状态
                orderMapper.updateShopDiscount(order.getId(), order.getShopDiscount());
                orderMapper.updateShopOriginalPrice(order.getId(), order.getOriginalPrice());
                service.updateOrderType(order.getId(), OrderService.CONFIRMING_STATUS);
            }
            //取餐凭证
            if (order.getConsumeType() == 2) {
                orderMapper.insertFetchMealCode(order.getId(), order.getFetchMealCode());
            }
            // 插入信息
            handleDishOrders(order.getId(), order.getUserId(), order.getStoreId(), order.getDishOrders());


            // 确认执行完毕
            channel.basicAck(msg.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            e.printStackTrace();
            // 第三个参数 false，拒绝重新入队，那么这条消息将进入死信
            channel.basicNack(msg.getMessageProperties().getDeliveryTag(), false, false);
            // 执行失败了
            b = false;
            // 抛出异常，事务回滚
            throw e;

        } finally {
            // 设置结果
            redis.opsForValue().set(RedisCacheManager.getOrderMessageCacheKey(om.getMessageId()), b ? 1 : -1);
            redis.expire(RedisCacheManager.getOrderMessageCacheKey(om.getMessageId()), 180, TimeUnit.SECONDS);
        }
    }

    /**
     * 更新用户使用折扣、插入订单菜品表、更新销量
     */
    public void handleDishOrders(String orderId, String userId, int storeId, List<Map<String, Object>> dishOrders) throws IOException {
        for (Map<String, Object> it : dishOrders) {
            int row = userMapper.updateUsedDiscountCount(
                    userId, (int) it.get("dishId"), (int) it.get("usedCount"));
            if (row == 0) {
                userMapper.insertUsedDiscountCount(
                        userId,
                        (int) it.get("dishId"), (int) it.get("usedCount"));
            }
            orderMapper.insertOrderDish(orderId, it);
            dishMapper.updateDishSale((int) it.get("dishId"), (int) it.get("dishNum"));
            //在 Redis 中存储当日的销量，由定时任务在每日 0点 写入
            redis.opsForHash().increment(RedisCacheManager.getTodayDateKey(), storeId, (int) it.get("dishNum"));
        }
    }


    /**
     * 扣减库存
     *
     * @param dishNumMap
     * @param storeId
     * @throws OrderAddException
     */
    public void deductionInventory(Map<Integer, Integer> dishNumMap, int storeId) throws OrderAddException {
        for (Map.Entry<Integer, Integer> it : dishNumMap.entrySet()) {
            // 乐观锁查询
            int row = dishMapper.optimisticDeductInventory(
                    storeId, it.getKey(), it.getValue());
            if (row == 0) {
                // 不捕获，让事务回滚
                throw new OrderAddException("库存不足，请重试");
            }
        }
    }

    /**
     * 将菜品添加至等待队列
     */
    public void addToWaitingQueue(Map<Integer, Integer> dishNumMap, int storeId) {
        long nowTs = System.currentTimeMillis();
        for (Map.Entry<Integer, Integer> it : dishNumMap.entrySet()) {
            //将菜品添加至等待队列中，对于多个相同菜品，重复添加单独实例以便处理
            // 同时附带菜品加入的时间戳
            for (int i = 0; i < (int) it.getValue(); i++) {
                redis.opsForList().leftPush(
                        RedisCacheManager.DISH_WAITING_QUEUE_KEY,
                        new Pair<Integer, Double>((Integer) it.getKey(), (double) nowTs));
            }
        }
    }


    /**
     * 订单库存回滚
     *
     * @param bytes
     * @param m
     * @param channel
     * @throws Exception
     */
    @RabbitListener(queues = {OrderRabbitMqConfig.ROLL_BACK_STOCK_QUEUE})
    public void rollBackStock(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws Exception {
        Map<Integer, Integer> dishNumMap = (Map<Integer, Integer>) JsonUtils.getObjectFromBytes(bytes);
        int storeId = dishNumMap.get(-1);
        try {
            for (Map.Entry<Integer, Integer> it : dishNumMap.entrySet()) {
                int id = it.getKey(), stock = it.getValue();
                redis.opsForHash().increment(RedisCacheManager.getDishStockCacheKey(storeId), id, stock);
            }
            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException e) {
            e.printStackTrace();
            channel.basicNack(
                    m.getMessageProperties().getDeliveryTag(), false, false);
            throw e;
        } catch (NullPointerException e) {
            redisManager.flushRedisDishStockCache(dishMapper.queryDishInfo(storeId), storeId);
            rollBackStock(bytes, m, channel);
            throw e;
        }
    }

    /**
     * 订单处理失败
     * @param bytes
     * @param m
     * @param channel
     * @throws IOException
     */
    @RabbitListener(queues = {OrderRabbitMqConfig.ORDER_ADD_DEAD_QUEUE})
    public void deadOrderMessageHandler(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws IOException {
        channel.basicQos(1);
        OrderMessage om = null;
        try {
            om = (OrderMessage) JsonUtils.getObjectFromBytes(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
        }
        //取消订单
        orderMapper.updateOrderType(om.getOrder().getId(), 9);
        com.happysnaker.pojo.Message message = com.happysnaker.pojo.Message.createSystemMessage("通知，您的订单处理失败", "服务器发送了一些不好的事情，因此没能正确处理您的订单，十分抱歉，您可以前往 我的-客服 寻求退款，订单ID为唯一凭证。订单ID：" + om.getOrder().getId(), om.getOrder().getUserId());
        messageMapper.insertMessage(message);
        if (messageMapper.updateUnReadUserMsgCount(message.getUserId(), 1) == 0) {
            messageMapper.insertUnReadUserMsgCount(message.getUserId(), 1);
        }

        // 发送消息回滚 redis
        om.getDishNumMap().put(-1, om.getOrder().getStoreId());
        rabbit.convertAndSend(OrderRabbitMqConfig.ROLL_BACK_STOCK_ROUTEING_KEY, om.getDishNumMap());
        channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
    }


    /**
     * 死信队列，尝试取消订单
     *
     * @param m
     * @param channel
     * @throws IOException
     */
    @RabbitListener(queues = {OrderRabbitMqConfig.ORDER_CANCEL_QUEUE})
    public void doCancelOrder(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws IOException {
        channel.basicQos(1);
        System.out.println("开始删除订单!!!");
        OrderMessage om = null;
        try {
            om = (OrderMessage) JsonUtils.getObjectFromBytes(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
        }
        String orderId = om.getOrder().getId();
        Order nowOrder = orderMapper.queryOrder(orderId);
        // 如果订单状态与数据库中不相等，说明已经被消费过了，或者用户支付了订单，直接确认即可
        if (nowOrder.getOrderType() != om.getOrder().getOrderType()) {
            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
            return;
        }

        // 发送消息回滚 redis
        Map<Integer, Integer> m1 = ((OrderServiceImpl) service).getDishNumMap(om.getOrder().getDishOrders());
        m1.put(-1, om.getOrder().getStoreId());
        rabbit.convertAndSend(OrderRabbitMqConfig.ROLL_BACK_STOCK_ROUTEING_KEY, m1);

        // 取消订单
        orderMapper.updateOrderType(om.getOrder().getId(), OrderService.CANCELLED_STATUS);

        com.happysnaker.pojo.Message message = com.happysnaker.pojo.Message.createSystemMessage("订单取消通知", "您有一份订单由于超时未支付而取消，订单ID为 " + om.getOrder().getId(), om.getOrder().getUserId());
        messageMapper.insertMessage(message);
        if (messageMapper.updateUnReadUserMsgCount(message.getUserId(), 1) == 0) {
            messageMapper.insertUnReadUserMsgCount(message.getUserId(), 1);
        }
        channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
    }
}
