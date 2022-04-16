package com.happysnaker.controller;

import com.alibaba.fastjson.JSONObject;
import com.happysnaker.controller.base.BaseController;
import com.happysnaker.exception.UpdateException;
import com.happysnaker.pojo.Dish;
import com.happysnaker.pojo.Order;
import com.happysnaker.pojo.OrderApplyTable;
import com.happysnaker.pojo.ShoppingCart;
import com.happysnaker.service.OrderService;
import com.happysnaker.utils.VerifyUtils;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Happysnaker
 * @description
 * @date 2021/10/22
 * @email happysnaker@foxmail.com
 */
@RestController
@Api(tags = {"测试接口"})
public class OrderController extends BaseController {
    private OrderService service;

    @Autowired
    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping(value = "/get_user_orders")
    String getUserOrders(HttpServletRequest request, HttpServletResponse response) {
        String ans = service.getUserOrders(request.getParameter(USER_ID_PARAM));
        return ans == null ? error(response) : ans;
    }

    @PostMapping(value = "/add_user_order")
    String addUserOrder(HttpServletRequest request, HttpServletResponse response) {
        if (VerifyUtils.isNullOrEmpty(request.getParameter(ORDER_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return null;
        }

        Order order = JSONObject.parseObject(request.getParameter(ORDER_PARAM), Order.class);
        int[] t = new int[]{2, 0, 1, 1};
        // 防止恶意用户伪造 orderType
        order.setOrderType(t[order.getConsumeType()]);

        Map res = null;
        try {
            order.setUserId(request.getParameter(USER_ID_PARAM));
            res = service.placeOrder(order);
        } catch (Exception e) {
            e.printStackTrace();
            Map map = new HashMap(2);
            map.put("code", 409);
            map.put("msg", e.getMessage());
            return JSONObject.toJSONString(map);
        }
        return JSONObject.toJSONString(res);
    }


    @GetMapping("/get_waiting_time")
    public String getWaitingTime(HttpServletRequest request, HttpServletResponse response) {
        if (!VerifyUtils.isNumber(request.getParameter(STORE_ID_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return null;
        }
        double waitingTime = service.getWaitingTime(Integer.parseInt(request.getParameter(STORE_ID_PARAM)));
        JSONObject object = new JSONObject();
        object.put("time", waitingTime);
        return object.toJSONString();
    }

    @PostMapping("/delete_order")
    public String deleteOrder(HttpServletRequest request, HttpServletResponse response) {
        if (VerifyUtils.isNullOrEmpty(request.getParameter(ORDER_ID_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return null;
        }
        try {
            service.deleteOrder(request.getParameter(ORDER_ID_PARAM));
            return OK;
        } catch (Exception e) {
            e.printStackTrace();
            return error(response);
        }
    }

    @PostMapping("/cancel_order")
    public String cancelOrder(HttpServletRequest request, HttpServletResponse response) {
        OrderApplyTable at = null;
        try {
            at = JSONObject.parseObject(request.getParameter("apply"), OrderApplyTable.class);
        } catch (ClassCastException e) {
            return error(response);
        }
        service.cancelOrder(at);
        return OK;
    }

    @PostMapping("/pay")
    public String pay(HttpServletRequest request, HttpServletResponse response) {
        if (VerifyUtils.isNullOrEmpty(request.getParameter(PAY_ID_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return PARAM_ERROR_MSG;
        }
        boolean b;
        try {
            b = service.pay(request.getParameter(PAY_ID_PARAM));
        } catch (UpdateException e) {
            e.printStackTrace();
            return error(response);
        }
        return b ? OK : error(response);
    }

    /**
     * 用户取消支付，订单状态设置为待支付，默认一段时间后取消
     *
     * @param request
     * @param response
     * @return
     */
    @PostMapping("/cancelpay")
    public String cancelPay(HttpServletRequest request, HttpServletResponse response) {
        if (VerifyUtils.isNullOrEmpty(request.getParameter(ORDER_ID_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return PARAM_ERROR_MSG;
        }
        try {
            service.cancelPay(request.getParameter(ORDER_ID_PARAM));
        } catch (UpdateException e) {
            e.printStackTrace();
            return error(response);
        }
        return OK;
    }


    @GetMapping("/is_occupied")
    public String isOccupied(int sid, int tid, HttpServletRequest request) {
        String userId = request.getParameter(USER_ID_PARAM);
        boolean b = service.isOccupied(sid, tid, userId);
        int code = !b ? 200 : 0;
        String msg = !b ? "桌位空闲" : "已被占有";
        return getResponseResult(code, msg, null);
    }


    @PostMapping("/relieve_occupied")
    public String relieveOccupied(int sid, int tid, int consumeType, HttpServletRequest request) {
        String userId = request.getParameter(USER_ID_PARAM);
        ShoppingCart cart = service.getCart(userId);
        if (cart != null) {
            if (cart.getConsumeType() == -1) {
                cart.setConsumeType(consumeType);
            }
            if (cart.getConsumeType() == consumeType &&
                    cart.getStoreId() == sid &&
                    cart.getTableId() == tid &&
                    !cart.getUserId().equals(userId)) {
                service.relieveOccupied(userId);
                return getResponseResult(200, "操作成功", null);
            }
        }
        return getResponseResult(200, "操作成功", null);
    }

    @PostMapping("/add_dish")
    public String addDishToCart(int sid, int tid, int consumeType, HttpServletRequest request) {
        String userId = request.getParameter(USER_ID_PARAM);
        String d = request.getParameter(DISH_PARAM);
        Dish dish = JSONObject.parseObject(d, Dish.class);
        try {
            ShoppingCart cart = service.getCart(userId);
            if (cart != null) {
                if (cart.getConsumeType() == -1) {
                    cart.setConsumeType(consumeType);
                }
                if (cart.getConsumeType() != consumeType ||
                        cart.getStoreId() != sid ||
                        cart.getTableId() != tid ||
                        !cart.getUserId().equals(userId)) {
                    throw new Exception("当前已存在购物车，请务重复消费");
                }
            }
            cart = service.addDish(sid, tid, userId, dish);
            return getResponseResult(200, "ok", cart);
        } catch (Exception e) {
            e.printStackTrace();
            return getResponseResult(0, e.getMessage(), null);
        }
    }

    @PostMapping("/remove_dish")
    public String removeDishToCart(int sid, int tid, HttpServletRequest request) {
        String userId = request.getParameter(USER_ID_PARAM);
        String d = request.getParameter(DISH_PARAM);
        Dish dish = JSONObject.parseObject(d, Dish.class);
        try {
            ShoppingCart cart = service.removeDish(sid, tid, userId, dish);
            if (cart == null) {
                return getResponseResult(0, "暂无数据", null);
            }
            // 特殊值，表示用户在加餐也页面减餐
            if (cart.getLastModify() == -1) {
                cart.setLastModify(System.currentTimeMillis());
                return getResponseResult(101, "若您正在加餐，我们不允许您取消原来已点的菜品，这是因为您原来点的菜品可能已经下锅或已完成，如果您确有需求，请前往前台与管理员说明情况。", cart);
            }
            return getResponseResult(200, "ok", cart);
        } catch (Exception e) {
            e.printStackTrace();
            return getResponseResult(0, e.getMessage(), null);
        }
    }

    @GetMapping("/get_cart")
    public String getOldCart(String orderId) {
        System.out.println("orderId = " + orderId);
        try {
            ShoppingCart cart = service.getCartFromOrder(orderId);
            return getResponseResult(200, "ok", cart);
        } catch (Exception e) {
            e.printStackTrace();
            return getResponseResult(0, e.getMessage(), null);
        }
    }


    @GetMapping("/get_order")
    public String getOrder(HttpServletRequest request) {
        String userId = request.getParameter(USER_ID_PARAM);
        try {
            Order order = service.getOrder(userId);
            return getResponseResult(200, "ok", order);
        } catch (Exception e) {
            e.printStackTrace();
            return getResponseResult(0, e.getMessage(), null);
        }
    }

    static int c = 5;

    @GetMapping("/check")
    public String isComplete(String orderId) {
        if (orderId == null) {
            return getResponseResult(400, "参数错误", null);
        }
        System.out.println("模拟排队.......");
        if (c < 0) {
            c = 3;
        }
        if (c-- != 0) {
            return getResponseResult(0, "正在排队", null);
        }
        try {
            int complete = service.isComplete(orderId);
            System.out.println("complete = " + complete);
            if (complete == 1) {
                return getResponseResult(200, "下单成功", null);
            } else if (complete == -1) {
                return getResponseResult(409, "下单失败", null);
            } else {
                return getResponseResult(0, "正在排队", null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage();
            msg = msg == null ? e.getCause().toString() : msg;
            return getResponseResult(400, msg, null);
        }
    }
}
