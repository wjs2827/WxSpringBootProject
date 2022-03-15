package com.happysnaker.strategy.impl;

import com.happysnaker.pojo.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 串行化的策略
 * @author Happysnaker
 * @description
 * @date 2022/3/15
 * @email happysnaker@foxmail.com
 */
@Component
public class SerializePlaceOrderStrategy extends AbstractPlaceOrderStrategy {
    @Override
    public Map doPlaceOrder(Order order) throws Exception {
        // ......
        return null;
    }

    @Override
    public int isComplete(String orderId) {
        return 1;
    }
}
