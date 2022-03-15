package com.happysnaker.strategy;

import com.happysnaker.pojo.Order;
import com.happysnaker.strategy.impl.HighConcurrencyPlaceOrderStrategy;
import com.happysnaker.strategy.impl.OptimisticPlaceOrderStrategy;
import com.happysnaker.strategy.impl.PessimisticPlaceOrderStrategy;
import com.happysnaker.strategy.impl.SerializePlaceOrderStrategy;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略模式上下文
 *
 * @author Happysnaker
 * @description
 * @date 2022/3/14
 * @email happysnaker@foxmail.com
 */
@Log4j2
@Component
public class PlaceOrderStrategyContent {
    static Map<Integer, PlaceOrderStrategy> cache = new ConcurrentHashMap<>();


    private PlaceOrderStrategy strategy;

    @Autowired
    public PlaceOrderStrategyContent(HighConcurrencyPlaceOrderStrategy highConcurrencyPlaceOrderStrategy, OptimisticPlaceOrderStrategy optimisticPlaceOrderStrategy, PessimisticPlaceOrderStrategy pessimisticPlaceOrderStrategy, SerializePlaceOrderStrategy serializePlaceOrderStrategy) {
        cache.put(0, highConcurrencyPlaceOrderStrategy);
        cache.put(1, optimisticPlaceOrderStrategy);
        cache.put(2, pessimisticPlaceOrderStrategy);
        cache.put(3, serializePlaceOrderStrategy);
    }

    public void setStrategy(int type) {
        this.strategy = cache.get(type);
    }

    public Map executePlaceOrderStrategy(Order order) throws Exception {
        log.info("开始执行下单策略，使用策略：" + strategy.getClass().getName());
        log.info("订单 ID：" + order.getId());

        Map map = strategy.doPlaceOrder(order);

        log.info("执行策略成功");
        return map;
    }

    public int isComplete(String oid) {
        return strategy.isComplete(oid);
    }
}
