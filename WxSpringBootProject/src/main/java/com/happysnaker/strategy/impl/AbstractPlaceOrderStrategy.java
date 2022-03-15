package com.happysnaker.strategy.impl;

import com.happysnaker.config.RedisCacheManager;
import com.happysnaker.pojo.ComboDish;
import com.happysnaker.service.BaseService;
import com.happysnaker.strategy.PlaceOrderStrategy;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Happysnaker
 * @description
 * @date 2022/3/14
 * @email happysnaker@foxmail.com
 */
public abstract class AbstractPlaceOrderStrategy extends BaseService implements PlaceOrderStrategy {
    @Autowired
    @Qualifier("myRabbitTemplate")
    protected RabbitTemplate rabbit;

    /**
     * 通用的方法
     *
     * @param dishOrders
     * @return
     * @see com.happysnaker.service.impl.OrderServiceImpl#getDishNumMap(List)
     */
    public Map<Integer, Integer> getDishNumMap(List<Map<String, Object>> dishOrders) {
        Map<Integer, Integer> m = new HashMap<>(8);
        for (var map : dishOrders) {
            int dishId = (int) map.get("dishId");
            int dishNum = (int) map.get("dishNum");
            if (dishId >= 100000) {
                List<ComboDish> list = comboMapper.queryComboDishById(dishId);
                for (ComboDish cMap : list) {
                    // 套餐内置的菜品个数 * 套餐个数
                    m.put((Integer) cMap.getDishId(), m.getOrDefault(cMap.getDishId(), 0) + cMap.getDishNum() * dishNum);
                }
            } else {
                m.put(dishId, m.getOrDefault(dishId, 0) + dishNum);
            }
        }
        return m;
    }

    @Override
    public void initMethod() {

    }


    @Override
    public int isComplete(String orderId) {
        if (redisManager.hasKey(RedisCacheManager.getOrderMessageCacheKey(orderId))) {
            return (int) redisManager.getForValue(RedisCacheManager.getOrderMessageCacheKey(orderId));
        }
        return 0;
    }


}
