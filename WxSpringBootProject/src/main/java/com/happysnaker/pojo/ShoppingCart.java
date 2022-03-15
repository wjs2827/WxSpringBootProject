package com.happysnaker.pojo;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 购物车，由桌号 + 店铺 ID 唯一标识
 * @author Happysnaker
 * @description
 * @date 2022/3/12
 * @email happysnaker@foxmail.com
 */
@Getter
@Setter
public class ShoppingCart implements Serializable {
    @Setter
    @Getter
    public static final class DishOrder {
        // 菜品 ID
        int id;
        // 菜品价格
        double price;
        // 菜品数量
        int num;
        // 菜品已使用的折扣数
        int discountUsedCount;
        // 菜品名称
        String name;

        public void increaseDiscountUsedCount() {
            this.discountUsedCount++;
        }

        public void decreaseDiscountUsedCount() {
            this.discountUsedCount--;
        }

        public void increaseDishNum() {
            this.num++;
        }

        public void decreaseDishNum() {
            this.num--;
        }
    }
    /**
     * 用户 ID
     */
    String userId;
    /**订单 ID*/
    String orderId;
    /**
     * 店铺 ID
     */
    int storeId;
    /**
     * 桌号
     */
    int tableId;
    /**消费类型*/
    int consumeType = -1;
    /**
     * 总金额，原价，非实际价格，实际价格应该用总金额减去优惠金额
     */
    double totalPrice;
    /**
     * 优惠金额
     */
    double discount;
    /**
     * 上一次修改的时间
     */
    long lastModify;
    /**
     * 是否已经锁定
     */
    boolean lock;
    /**
     * 是否已下单
     */
    boolean complete;
    /**
     * 所点的菜品列表，这里的 key 其实是 int 类型的菜品 ID，但由于此类将作为 JSON 字符串返回给前端，JSON 中 key 必须为 String 类型
     */
    Map<String, DishOrder> dishOrders = new ConcurrentHashMap<>();
    /**
     * 加菜列表
     */
    Map<String, DishOrder>  newDishOrders = new ConcurrentHashMap<>();

    @Override
    public String toString() {
        return "ShoppingCart{" +
                "userId='" + userId + '\'' +
                ", orderId='" + orderId + '\'' +
                ", storeId=" + storeId +
                ", tableId=" + tableId +
                ", consumeType=" + consumeType +
                ", totalPrice=" + totalPrice +
                ", discount=" + discount +
                ", lastModify=" + lastModify +
                ", lock=" + lock +
                ", complete=" + complete +
                ", dishOrders=" + dishOrders +
                ", newDishOrders=" + newDishOrders +
                '}';
    }
}
