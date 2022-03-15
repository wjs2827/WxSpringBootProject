package com.happysnaker.strategy;

import com.happysnaker.pojo.Order;

import java.util.Map;

/**
 * 下单时策略
 * @author Happysnaker
 * @description
 * @date 2022/3/14
 * @email happysnaker@foxmail.com
 */
public interface PlaceOrderStrategy {
    /**
     * 添加订单
     * @param order
     * @return 返回支付单号和订单号
     */
    Map doPlaceOrder(Order order) throws Exception;

    /**
     * 查询订单是否完成
     * @param orderId
     * @return 1 成功，0 正在排队或正在执行，-1 失败
     */
    int isComplete(String orderId);

    /**
     * 初始化方法
     */
    void initMethod();
}
