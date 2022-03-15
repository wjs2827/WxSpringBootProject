package com.happysnaker.controller;

import com.happysnaker.config.RedisCacheManager;
import com.happysnaker.controller.base.BaseController;
import com.happysnaker.service.UserService;
import com.happysnaker.utils.VerifyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
public class UserController extends BaseController {
    private UserService service;

    @Autowired
    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/get_user_info")
    public String getUserInfo(HttpServletRequest request) {
        return service.getUserInfo(request.getParameter(USER_ID_PARAM));
    }

    @GetMapping(value = "/get_user_marked_dish")
    public String getUserMarkedDishes(HttpServletRequest request) {
        //不用检查参数， userId 拦截器已经验证了
        String res = service.getUserMarkedDish(request.getParameter(USER_ID_PARAM));
        logInfo(request, "getUserMarkedDish", res);
        return res;
    }

    @GetMapping(value = "/get_collected_stores")
    public String getUserCollectedStores(HttpServletRequest request) {
        //不用检查参数， userId 拦截器已经验证了
        String res = service.getCollectedStores(request.getParameter(USER_ID_PARAM));
        logInfo(request, "getUserCollectedStores", res);
        return res;
    }

    @GetMapping(value = "/get_used_discount_num")
    public String getUsedDiscountNum(HttpServletRequest request) {
        return service.getUsedDiscountCount(request.getParameter(USER_ID_PARAM));
    }

    @PostMapping(value = "/add_user_like_dish")
    public String addUserLikeDish(HttpServletRequest request, HttpServletResponse response) {
        if (VerifyUtils.isNullOrEmpty(request.getParameter(USER_ID_PARAM)) || !VerifyUtils.isNumber(request.getParameter(DISH_ID_PARAM))) {
            response.setStatus(PARAM_ERROR_STATUS);
            return null;
        }
        int dishId = Integer.parseInt(request.getParameter(DISH_ID_PARAM));
        String res = service.addUserLikeDish(request.getParameter(USER_ID_PARAM), dishId);

        if (res != null && res.equals("OK")) {
            String key = RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY;
            synchronized (this) {
                if (!redis.opsForHash().hasKey(key, dishId)) {
                    redis.opsForHash().put(key, dishId, 0);
                }
                try {
                    int prev = (int) redis.opsForHash().get(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId);
                    System.out.println("prev = " + prev);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                redis.opsForHash().increment(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId, 1);
                int now = (int) redis.opsForHash().get(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId);
                System.out.println("now = " + now);
                if (now >= DEFAULT_DISH_LIKE_FLUSH_NUM) {
                    notifyObservers(0, dishId, now);
                    redis.opsForHash().put(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId, 0);
                }

            }
        }
        return res;
    }

    @PostMapping(value = "/remove_user_like_dish")
    public String removeUserLikeDish(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(DISH_ID_PARAM))) {
            return null;
        }
        int dishId = Integer.parseInt(request.getParameter(DISH_ID_PARAM));
        String res = service.removeUserLikeDish(request.getParameter(USER_ID_PARAM), dishId);
        if (res != null && res.equals("OK")) {
            synchronized (this) {
                redis.opsForHash().increment(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId, -1);
                int now = (int) redis.opsForHash().get(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId);
                if (now <= -DEFAULT_DISH_LIKE_FLUSH_NUM) {
                    notifyObservers(0, dishId, now);
                    redis.opsForHash().put(RedisCacheManager.DISH_LIKE_NUM_CACHE_KEY, dishId, 0);
                }
            }
        }
        return res;
    }

    @PostMapping(value = "/add_user_collected_dish")
    public String addUserCollectedDish(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(DISH_ID_PARAM))) {
            return null;
        }

        return service.addUserCollectedDish(request.getParameter(USER_ID_PARAM), Integer.parseInt(request.getParameter(DISH_ID_PARAM)));
    }

    @PostMapping(value = "/remove_user_collected_dish")
    public String removeUserCollectedDish(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(DISH_ID_PARAM))) {
            return null;
        }

        return service.removeUserCollectedDish(request.getParameter(USER_ID_PARAM), Integer.parseInt(request.getParameter(DISH_ID_PARAM)));
    }

    @PostMapping(value = "/add_user_will_buy_dish")
    public String addUserWillBuyDish(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(DISH_ID_PARAM))) {
            return null;
        }

        return service.addUserWillBuyDish(request.getParameter(USER_ID_PARAM), Integer.parseInt(request.getParameter(DISH_ID_PARAM)));
    }

    @PostMapping(value = "/remove_user_will_buy_dish")
    public String removeUserWillBuyDish(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(DISH_ID_PARAM))) {
            return null;
        }

        return service.removeUserWillBuyDish(request.getParameter(USER_ID_PARAM), Integer.parseInt(request.getParameter(DISH_ID_PARAM)));
    }

    @PostMapping(value = "/add_user_collected_store")
    public String addUserCollectedStore(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(STORE_ID_PARAM))) {
            return null;
        }
        return service.addUserCollectedStore(request.getParameter(USER_ID_PARAM), Integer.parseInt(request.getParameter(STORE_ID_PARAM)));
    }

    @PostMapping(value = "/remove_user_collected_store")
    public String removeUserCollectedStore(HttpServletRequest request) {
        if (!VerifyUtils.isNumber(request.getParameter(STORE_ID_PARAM))) {
            return null;
        }

        return service.removeUserCollectedStore(request.getParameter(USER_ID_PARAM), Integer.parseInt(request.getParameter(STORE_ID_PARAM)));
    }
}
