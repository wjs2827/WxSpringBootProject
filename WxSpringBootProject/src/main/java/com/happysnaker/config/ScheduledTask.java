package com.happysnaker.config;

import com.happysnaker.mapper.DiscountMapper;
import com.happysnaker.mapper.DishMapper;
import com.happysnaker.mapper.StoreMapper;
import com.happysnaker.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 定时任务
 * @author happysnakers
 */
@Component
public class ScheduledTask {
    @Autowired
    UserMapper userMapper;

    @Autowired
    DishMapper dishMapper;

    @Autowired
    StoreMapper storeMapper;

    @Autowired
    DiscountMapper discountMapper;


    @Qualifier("myRedisTemplate")
    @Autowired
    RedisTemplate redis;


    /**
     * 写入用户喜欢、收藏、待选信息，一切以 redis 中为准，移除 redis 中没有的，增加 redis 中有的
     */
    @Scheduled(cron = "0 0 3,15 * * ?")
    public void doTask1() {
        System.out.println("执行定时任务.....");
        for (String userId : userMapper.queryAllUserIds()) {
            List<Integer> store = userMapper.queryCollectedStore(userId);
            List<Integer> collectedDish = userMapper.queryCollectedDish(userId);
            List<Integer> favoriteDish = userMapper.queryFavoriteDish(userId);
            List<Integer> willBuyDish = userMapper.queryWillBuyDish(userId);
            boolean b1 = redis.hasKey(RedisCacheManager.getUserLikeDishCacheKey(userId));
            boolean b2 = redis.hasKey(RedisCacheManager.getUserCollectedDishCacheKey(userId));
            boolean b3 = redis.hasKey(RedisCacheManager.getUserWillBuyDishCacheKey(userId));
            boolean b4 = redis.hasKey(RedisCacheManager.getUserCollectedStoreCacheKey(userId));
            if (b1 || b2 || b3) {
                // 遍历所有菜品 ID
                for (Integer id : dishMapper.queryAllDishId()) {
                    if (b1) {
                        boolean val1 = redis.opsForValue().getBit(
                                RedisCacheManager.getUserLikeDishCacheKey(userId), id);

                        if (collectedDish.indexOf(id) == -1 && val1) {
                            // redis 中有，数据库中没有，增加
                            userMapper.addCollectedDish(userId, id);
                        } else if (collectedDish.indexOf(id) != -1 && !val1) {
                            // redis 中无，数据库中有，移除
                            userMapper.removeCollectedDish(userId, id);
                        }
                    }
                    if (b2) {
                        boolean val2 = redis.opsForValue().getBit(RedisCacheManager.getUserCollectedDishCacheKey(userId), id);
                        if (favoriteDish.indexOf(id) == -1 && val2) {
                            userMapper.addFavoriteDish(userId, id);
                        } else if (favoriteDish.indexOf(id) != -1 && !val2) {
                            userMapper.removeFavoriteDish(userId, id);
                        }
                    }
                    if (b3) {
                        boolean val3 = redis.opsForValue().getBit(RedisCacheManager.getUserWillBuyDishCacheKey(userId), id);
                        if (willBuyDish.indexOf(id) == -1 && val3) {
                            userMapper.addWillBuyDish(userId, id);
                        } else if (willBuyDish.indexOf(id) != -1 && !val3) {
                            userMapper.removeWillBuyDish(userId, id);
                        }
                    }
                }
            }
            if (b4) {
                for (Integer id : storeMapper.queryAllStoreId()) {
                    boolean val1 = redis.opsForValue().getBit(RedisCacheManager.getUserCollectedStoreCacheKey(userId), id);
                    if (store.indexOf(id) == -1 && val1) {
                        userMapper.addCollectedStore(userId, id);
                    } else if (store.indexOf(id) != -1 && !val1) {
                        userMapper.removeCollectedStore(userId, id);
                    }
                }
            }
            redis.delete(RedisCacheManager.getUserLikeDishCacheKey(userId));
            redis.delete(RedisCacheManager.getUserCollectedDishCacheKey(userId));
            redis.delete(RedisCacheManager.getUserWillBuyDishCacheKey(userId));
            redis.delete(RedisCacheManager.getUserCollectedStoreCacheKey(userId));
        }
    }

    // 11.55 定时写入今日销量，清除等待队列，更新用户每日折扣数目
    @Scheduled(cron = "0 55 23 * * ?")
    public void doTask2() {
        System.out.println("执行定时任务.....");
        for (Integer id : storeMapper.queryAllStoreId()) {
            int num = 0;
            if (redis.hasKey(RedisCacheManager.getTodayDateKey())) {
                num = (int) redis.opsForHash().get(RedisCacheManager.getTodayDateKey(), id);
            }
            dishMapper.insertSaleLog(new Timestamp(RedisCacheManager.getTodayDate().getTime()), id, num);
        }

        redis.delete(RedisCacheManager.DISH_WAITING_QUEUE_KEY);

        for (String userId : userMapper.queryAllUserIds()) {
            Map<Integer, Map> map = userMapper.queryUserUsedDiscountCountInAllDish(userId);
            for (Map.Entry<Integer, Map> it : map.entrySet()) {
                int dishId = it.getKey();
                userMapper.updateUsedDiscountCountByANewVal(userId, dishId, 0);
            }
        }

    }
}
