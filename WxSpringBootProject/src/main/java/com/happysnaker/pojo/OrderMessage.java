package com.happysnaker.pojo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * @author Happysnaker
 * @description
 * @date 2021/12/13
 * @email happysnaker@foxmail.com
 */
@Data
public class OrderMessage implements Serializable {
    /**
     * 需要扣减的库存，key 是菜品 ID，val 是需要扣减的库存数
     */
    Map<Integer, Integer> dishNumMap;
    Order order;
    /**
     * 标识消息，默认以订单 ID 标识，可通过此 ID 查询订单处理情况
     */
    String messageId;

    public OrderMessage(Map<Integer, Integer> dishStockMap, Order order) {
        this.dishNumMap = dishStockMap;
        this.order = order;
        messageId = order.getId();
    }
}
