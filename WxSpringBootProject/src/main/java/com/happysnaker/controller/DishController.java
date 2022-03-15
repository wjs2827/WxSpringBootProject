package com.happysnaker.controller;

import com.alibaba.fastjson.JSONObject;
import com.happysnaker.controller.base.BaseController;
import com.happysnaker.service.DishService;
import com.happysnaker.utils.JsonUtils;
import com.happysnaker.utils.VerifyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author Happysnaker
 * @description
 * @date 2021/10/22
 * @email happysnaker@foxmail.com
 */
@RestController
public class DishController extends BaseController {
    private DishService service;

    @Autowired
    public DishController(DishService service) {
        this.service = service;
    }

    /**
     * 获取首页菜品列表信息
     * @param request
     * @param response
     * @return
     */
    @GetMapping(value = "/get_index_dish_info")
    public String getIndexDishInfo(HttpServletRequest request, HttpServletResponse response) {
        return service.getIndexDishInfo();
    }

    /**
     * 获取点餐页面的菜品列表，这额外包括库存、折扣等信息
     * @param request
     * @param response
     * @return
     */
    @GetMapping(value = "/get_order_dish_info")
    public String getOrderDishInfo(HttpServletRequest request, HttpServletResponse response) {

        if (!VerifyUtils.isNumber(request.getParameter(STORE_ID_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return null;
        }
        return service.getOrderDishInfo(Integer.parseInt(request.getParameter(STORE_ID_PARAM)));
    }


    /**
     * 获取菜品分类，例如 热销、必点、下酒菜 等
     * @param request
     * @param response
     * @return
     */
    @GetMapping(value = "/get_dish_classification")
    public String getDishClassification(HttpServletRequest request, HttpServletResponse response) {
        return JsonUtils.listAddToJsonObject(new JSONObject(), service.getDishClassification()).toJSONString();
    }

    /**
     * 获取用户收藏的菜品或套餐列表
     * @param request
     * @param response
     * @return
     */
    @GetMapping("/get_user_collected_dishes")
    public String getUserCollectedDishInfo(HttpServletRequest request, HttpServletResponse response) {
        return JsonUtils.listAddToJsonObject(new JSONObject(),
                service.getUserCollectedDishes(request.getParameter(USER_ID_PARAM))).toJSONString();
    }

    /**
     * 获取菜品喜欢数目变更列表
     * @return
     */
    @GetMapping("/get_dish_like_num")
    public String getDishLikeNumList() {
        return JsonUtils.listAddToJsonObject(new JSONObject(),
                service.getDishLikeNumDeltaList()).toJSONString();
    }
}
