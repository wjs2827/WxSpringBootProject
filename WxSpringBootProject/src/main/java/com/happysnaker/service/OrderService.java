package com.happysnaker.service;

import com.happysnaker.exception.OrderAddException;
import com.happysnaker.exception.ReadWriterLockException;
import com.happysnaker.exception.UpdateException;
import com.happysnaker.pojo.Dish;
import com.happysnaker.pojo.Order;
import com.happysnaker.pojo.OrderApplyTable;
import com.happysnaker.pojo.ShoppingCart;

import java.sql.Timestamp;
import java.util.Map;

/**
 * @author Happysnaker
 * @description
 * @date 2021/10/22
 * @email happysnaker@foxmail.com
 */
public interface OrderService {
    /**
     * 根据 consumeType 和 当前订单状态 获取下一阶段的订单状态
     * <p>consume_type 0 对应扫码点餐  经历流程：确认中 - 待支付 - 已完成<br/>
     * <p>
     * consume_type 1 对应到店消费 经历流程：支付保证金 - 确认中 - 备餐中 - 待用餐 - 待支付 - 已完成<br/>
     * <p>
     * consume_type 2 对应到店自取 经历流程：待支付 - 确认中 - 备餐中 - 待取餐 - 已完成<br/>
     * <p>
     * consume_type 3 对应外卖 经历流程：待支付 - 确认中 - 备餐中 - 配送中 - 已完成<br/>
     *
     *
     * @param consumeType 消费类型
     * @param orderType 订单状态
     * @return orderType: 0待点餐, 1待支付, 2确认中，3备餐中，4待用餐，5待取餐，6配送中，7已完成，8取消确认中，9已取消
     *
     */
    default int getNextStatus(int consumeType, int orderType) {
        //如果消费类型是 -1，说明前端不想让我们正常走到下个状态，那么以前端传递过来的状态为准
        if (consumeType == -1) {
            return orderType;
        }
        if (orderType == CANCELING_STATUS) {
            return CANCELLED_STATUS;
        }
        String[] ss = new String[]{"217", "023417", "12357", "12367"};
        String s = ss[consumeType];
        int nowIndex = s.indexOf(String.valueOf(orderType));
        return s.charAt(nowIndex + 1) - '0';
    }

    public static String[] typeMap = new String[]{"待点餐", "待支付", "确认中", "备餐中", "待用餐", "待取餐", "配送中", "已完成", "取消中", "已取消"};
    /**
     * 待支付保证金
     */
    int TO_BE_PAID_MARGIN_STATUS = 0;
    /**
     * 待支付
     */
    int TO_BE_PAID_STATUS = 1;
    /**
     * 确认中
     */
    int CONFIRMING_STATUS = 2;
    /**
     * 备餐中
     */
    int PREPARING_MEAL_STATUS = 3;
    /**
     * 待用餐
     */
    int TO_HAVE_A_MEAL_STATUS = 4;
    /**
     * 待取餐
     */
    int MEAL_WAITING_STATUS = 5;
    /**
     * 配送中
     */
    int IN_DELIVERY_STATUS = 6;
    /**
     * 已完成
     */
    int COMPLETED_STATUS = 7;
    /**
     * 取消中
     */
    int CANCELING_STATUS = 8;
    /**
     * 已取消
     */
    int CANCELLED_STATUS = 9;


    /**
     * 更改订单的状态
     *
     * @param orderId   订单ID
     * @param newStatus 新的状态
     * @return "ok" if ok, null else
     */
    void updateOrderType(String orderId, int newStatus) throws UpdateException;

    /**
     * 更改订单的结束时间
     * @param orderId
     * @param ts
     * @throws UpdateException
     */
    void updateFinalTime(String orderId, Timestamp ts) throws UpdateException;

    /**
     * 获取用户的订单信息
     *
     * @param userId 用户ID
     * @return List Form TO JSON
     */
    String getUserOrders(String userId);

    /**
     * 支付
     * @param payId
     * @return 成功返回 true，失败返回 false
     * @throws UpdateException
     */
    boolean pay(String payId) throws UpdateException;

    /**
     * 取消支付
     * @param orderId
     * @throws UpdateException
     */
    void cancelPay(String orderId) throws UpdateException;

    Map placeOrder(Order order) throws Exception;

    /**
     * 当用户点击结算时，我们需要返回订单供用户确认
     * @param userId
     * @return
     * @throws OrderAddException
     */
    Order getOrder(String userId) throws OrderAddException;

    /**
     * 下单，添加订单
     * @param userId
     * @param form
     * @return 返回支付号、生成的订单 ID 给前端
     * @throws OrderAddException
     * @throws ReadWriterLockException
     * @deprecated 已弃用，现在由后端存储订单信息，涉及金额的敏感数据都由后端做
     */
    @Deprecated
    Map addUserOrder(String userId, Order form) throws OrderAddException, ReadWriterLockException;


    /**
     * 获取用户需要等待的时间
     *
     * @param storeId 店铺ID
     * @return 返回一个JSON: {’time‘: val}，val 为需要等待的时间(分钟)
     */
    double getWaitingTime(int storeId);

    /**
     * 删除订单
     * @param id
     */
    void deleteOrder(String id);

    /**
     * 取消订单
     * @param at
     */
    void cancelOrder(OrderApplyTable at);


    /**
     * 店铺中桌位上是否已经存在一个订单
     * @param sid 店铺 ID
     * @param tid 桌号
     * @param uid
     * @return
     */
    boolean isOccupied(int sid, int tid, String uid);

    /**
     * 用户离开点餐页面时需要解除对桌位的占有、以及删除购物车
     * @param uid
     */
    void relieveOccupied(String uid);

    /**
     * 向购物车中添加一个菜
     * @param sid 店铺 ID
     * @param tid 桌号，当不是扫码点餐时为 -1
     * @param did 菜品 ID
     * @return
     */
    ShoppingCart addDish(int sid, int tid, String uid, Dish dish) throws OrderAddException;

    /**
     * 向购物车中移除一个菜
     * @param sid 店铺 ID
     * @param tid 桌号，当不是扫码点餐时为 -1
     * @param did 菜品 ID
     * @param uid
     * @return
     */
    ShoppingCart removeDish(int sid, int tid, String uid, Dish dish);

    /**
     * 从订单中生成一个 cart，用以服务加餐用户
     * @param oid
     * @return
     */
    ShoppingCart getCartFromOrder(String oid);


    /**
     * 查询订单是否完成
     * @param orderId
     * @return 1 已完成，0 正在处理，-1 失败
     */
    int isComplete(String orderId);


    /**
     * 获取购物车
     * @param uid
     * @return
     */
    ShoppingCart getCart(String uid);
}
