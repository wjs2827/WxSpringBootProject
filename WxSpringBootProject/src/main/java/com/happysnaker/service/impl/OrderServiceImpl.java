package com.happysnaker.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.happysnaker.config.OrderRabbitMqConfig;
import com.happysnaker.config.RedisCacheManager;
import com.happysnaker.exception.OrderAddException;
import com.happysnaker.exception.ReadWriterLockException;
import com.happysnaker.exception.UpdateException;
import com.happysnaker.pojo.*;
import com.happysnaker.service.BaseService;
import com.happysnaker.service.OrderService;
import com.happysnaker.strategy.PlaceOrderStrategyContent;
import com.happysnaker.utils.JsonUtils;
import com.happysnaker.utils.Pair;
import com.happysnaker.utils.VerifyUtils;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author Happysnaker
 * @description
 * @date 2021/10/22
 * @email happysnaker@foxmail.com
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Configuration
@EnableRabbit
public class OrderServiceImpl extends BaseService implements OrderService {

    /**
     * 这个参数是前端显示流程的对应索引，可以通过对应的 consume_type 访问，通过 indexOf 方法查询索引
     * <p>consume_type 0 对应扫码点餐  经历流程：待点餐 - 确认中 - 待支付 - 已完成<br/>
     * <p>
     * consume_type 1 对应到店消费 经历流程：支付保证金 - 确认中 - 备餐中 - 待用餐 - 待支付 - 已完成<br/>
     * <p>
     * consume_type 2 对应到店自取 经历流程：待支付 - 确认中 - 备餐中 - 待取餐 - 已完成<br/>
     * <p>
     * consume_type 3 对应外卖 经历流程：待支付 - 确认中 - 备餐中 - 配送中 - 已完成<br/>
     * <p>
     * orderType: 0待点餐, 1待支付, 2确认中，3备餐中，4待用餐，5待取餐，6配送中，7已完成，8取消确认中，9已取消<br/>
     * </p>
     */
    private Map<Integer, String> statusMap;
    private RabbitTemplate rabbit;

    static Map<String, ShoppingCart> cartCache = new ConcurrentHashMap<>();

    PlaceOrderStrategyContent content = null;


    int placeOrderStrategy = 0;

    @Value("${strategy.place-order}")
    public void setPlaceOrderStrategy(int placeOrderStrategy) {
        content.setStrategy(placeOrderStrategy);
    }

    @Autowired
    public OrderServiceImpl(@Qualifier("myRabbitTemplate") RabbitTemplate rabbit, PlaceOrderStrategyContent content) {
        this.rabbit = rabbit;
        statusMap = new HashMap<>();
        statusMap.put(0, "217");
        statusMap.put(1, "023417");
        statusMap.put(2, "12357");
        statusMap.put(3, "12367");

        this.content = content;
    }


    /**
     * 将订单转成 JSON，这需要注入一些额外的信息
     *
     * @param f
     * @return
     */
    private JSONObject order2Json(Order f) {
        JSONObject obj = JsonUtils.getJSONObject(f);
        Store store = storeMapper.getStore(f.getStoreId());
        //注入店铺信息
        obj.put("storeName", store.getName());
        obj.put("storeImg", store.getImg());
        //注入流程图状态
        obj.put("nowStatus", statusMap.get(f.getConsumeType()).indexOf(String.valueOf(f.getOrderType())));
        return obj;
    }

    @Override
    public void updateOrderType(String orderId, int newStatus) throws UpdateException {
        int row = orderMapper.updateOrderType(orderId, newStatus);
        if (row == 0) {
            throw new UpdateException("更新订单状态失败: " + orderId);
        }
    }

    @Override
    public void updateFinalTime(String orderId, Timestamp ts) throws UpdateException {
        int row = orderMapper.updateOrderFinalTime(orderId, ts);
        if (row == 0) {
            throw new UpdateException("更新订单状态失败: " + orderId);
        }
    }

    @Override
    public String getUserOrders(String userId) {
        List<Order> list = orderMapper.queryOrdersByUserId(userId);
        JSONObject jsonObject = new JSONObject();
        JSONArray jsonArray = new JSONArray();
        // 按照时间排序
        Collections.sort(list, (o1, o2) -> {
            return o1.getCreateTime().getTime() > o2.getCreateTime().getTime() ? -1 : 1;
        });
        for (Order order : list) {
            order.setDishOrders(orderMapper.queryOrderDish(order.getId()));
            jsonArray.add(order2Json(order));
        }
        jsonObject.put("orders", jsonArray);
        return jsonObject.toJSONString();
    }


    @Override
    public boolean pay(String payId) throws UpdateException {
        String orderId = orderMapper.queryOrderId(payId);
        Order order = orderMapper.queryOrder(orderId);

        // 进行复杂的支付后，支付成功，更订单至下一状态
        int nextStatus = getNextStatus(order.getConsumeType(), order.getOrderType());
        updateOrderType(orderId, nextStatus);

        if (order.getConsumeType() == 1) {
            // 保证金按道理在生成支付订单的时候就已经计算好了，这里没办法模拟
            try {
                orderMapper.insertMargin(orderId, 0.2 * (order.getOriginalPrice() - order.getShopDiscount() - order.getCouponDiscount()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    @Override
    public void cancelPay(String orderId) throws UpdateException {
        Order order = orderMapper.queryOrder(orderId);
        // 用户取消支付，更新至待支付状态或待支付保证金状态，然后利用消息队列完成取消
        order.setOrderType(order.getConsumeType() == 1 ? TO_BE_PAID_MARGIN_STATUS : TO_BE_PAID_STATUS);
        updateOrderType(orderId, order.getOrderType());
        // 发送消息到业务处
        rabbit.convertAndSend(OrderRabbitMqConfig.ORDER_PENDING_PAYMENT_ROUTEING_KEY, new OrderMessage(null, order));
    }


    /**
     * 生成菜品库存哈希
     *
     * @param dishOrders
     * @return key -> dishId，val -> stock
     */
    public Map<Integer, Integer> getDishNumMap(List<Map<String, Object>> dishOrders) {
        Map<Integer, Integer> m = new HashMap<>(8);
        for (Map<String, Object> map : dishOrders) {
            int dishId = (int) map.get("dishId");
            int dishNum = (int) map.get("dishNum");
            if (dishId >= 100000) {
                List<ComboDish> list = comboMapper.queryComboDishById(dishId);
                for (ComboDish cMap : list) {
                    // 套餐内置的菜品个数 * 套餐个数
                    m.put((Integer) cMap.getDishId(), m.getOrDefault(cMap.getDishId(), 0) + cMap.getDishNum() * dishNum);
                }
            } else {
                m.put(dishId, m.getOrDefault(dishId, 0) + dishNum);
            }
        }
        return m;
    }

    private boolean checkStock(Map<Integer, Integer> m, int storeId) {
        try {
            for (Map.Entry<Integer, Integer> it : m.entrySet()) {
                int id = it.getKey(), stock = it.getValue();
                if ((int) redisManager.getForHash(RedisCacheManager.getDishStockCacheKey(storeId), id) < stock) {
                    return false;
                }
            }
            return true;
        } catch (NullPointerException e) {
            redisManager.flushRedisDishStockCache(
                    dishMapper.queryDishInfo(storeId), storeId);
            return checkStock(m, storeId);
        }
    }


    /**
     * <p>2022-3-14 更新，已废弃原来的逻辑</p>
     * <p>使用策略模式应对不同的情景，这里实现了平时使用和高并发场景两种策略</p>
     * <p>原先的代码使用了 redis 提前缓存库存，适用与高并发条件</p>
     *
     * @return 返回 payId 和 orderId 给用户
     * @see #addUserOrder(String, Order)
     */
    @Override
    public Map placeOrder(Order order) throws Exception {
        if (cartCache.containsKey(order.getUserId())) {
            synchronized (cartCache.get(order.getUserId())) {
                if (cartCache.containsKey(order.getUserId())) {
                    Map map = content.executePlaceOrderStrategy(order);
                    cartCache.remove(order.getUserId());
                    return map;
                }
            }
        }
        throw new Exception("用户已下单或不存在订单，请勿重复下单");
    }


    /**
     * <p>当用户点击结算时，我们需要返回订单供用户确认信息</p>
     * <p>这一步并不会下单，只有当用户确认时才会进行下单，触发 {@link #placeOrder(Order)} 方法</p>
     *
     * @param userId
     * @return
     * @throws OrderAddException
     */
    @Override
    public Order getOrder(String userId) throws OrderAddException {
        ShoppingCart cart = null;
        for (Map.Entry<String, ShoppingCart> it : cartCache.entrySet()) {
            if (it.getValue().getUserId().equals(userId)) {
                cart = it.getValue();
                break;
            }
        }
        if (cart == null) {
            throw new OrderAddException("未查询到订单信息");
        }
        // 根据 cart 生成订单，这一步略有繁琐
        Order order = new Order();
        if (cart.isComplete()) {
            order.setId(cart.getOrderId());
        } else {
            order.setIsNew(true);
        }
        order.setDishOrders(getDishOrders(cart));
        order.setOrderType(cart.getConsumeType());
        order.setUserId(userId);
        order.setShopDiscount(cart.getDiscount());
        order.setOriginalPrice(cart.getTotalPrice());
        order.setStoreId(cart.getStoreId());
        order.setTable(cart.getTableId());
        order.setConsumeType(cart.getConsumeType());

        if (order.getConsumeType() == 0) {
            // 扫码点餐，设置为确认中
            order.setOrderType(CONFIRMING_STATUS);
        } else if (order.getConsumeType() == 1) {
            System.out.println("cart.isComplete() = " + cart.isComplete());
            // 到店消费，如果未支付则设置为支付保证金，否则设置为待确认
            order.setOrderType(!cart.isComplete() ? TO_BE_PAID_MARGIN_STATUS : CONFIRMING_STATUS);
        } else {
            // 外卖或自取，待支付
            order.setOrderType(TO_BE_PAID_STATUS);
        }
        return order;
    }

    private List<Map<String, Object>> getDishOrders(ShoppingCart cart) {
        List<Map<String, Object>> dishOrders = new ArrayList<>();
        for (Map.Entry<String, ShoppingCart.DishOrder> it : cart.getDishOrders().entrySet()) {
            Map<String, Object> dishOrder = new HashMap<>();
            dishOrder.put("dishId", Integer.parseInt(it.getKey()));
            dishOrder.put("dishNum", it.getValue().getNum());
            dishOrder.put("dishPrice", it.getValue().getPrice());
            dishOrder.put("usedCount", it.getValue().getDiscountUsedCount());
            dishOrder.put("dishName", it.getValue().getName());
            dishOrders.add(dishOrder);
        }
        for (Map.Entry<String, ShoppingCart.DishOrder> it : cart.getNewDishOrders().entrySet()) {
            Map<String, Object> dishOrder = new HashMap<>();
            dishOrder.put("dishId", Integer.parseInt(it.getKey()));
            dishOrder.put("dishNum", it.getValue().getNum());
            dishOrder.put("dishPrice", it.getValue().getPrice());
            dishOrder.put("usedCount", it.getValue().getDiscountUsedCount());
            dishOrder.put("dishName", it.getValue().getName());
            dishOrder.put("isAdd", true);
            dishOrders.add(dishOrder);
        }
        return dishOrders;
    }


    /**
     * 添加订单要做的事有<br/>
     * <ul>
     *  <li> 计算订单ID<br/></li>
     *  <li>扣减库存，扣减库存必须保证库存充足<br/></li>
     * <li> 增加用户使用的折扣数目<br/></li>
     * <li>增加用户会员积分<br/></li>
     * <li> 扣减优惠券，目前不支持<br/></li>
     * <li>向 order 表 和 order_dish 表写订单数据<br/></li>
     *  <li>增加对应菜品销量<br/></li>
     * <li> 将菜品写入等待队列，以便计算新用户下单后需要等待的时常<br/></li>
     * </ul>
     */
    @Override
    public Map addUserOrder(String userId, Order order) throws OrderAddException, ReadWriterLockException {
        System.out.println("order = " + order);
        if (order == null) {
            return null;
        }

        if (!order.getIsNew()) {
            order.setIsNew(false);
            System.out.println("旧！");
            //这是一个旧订单，由用户继续加餐而来
            handleOldOrder(order);
            return null;
        }
        order.setIsNew(true);

        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        order.setCreateTime(timestamp);

        //生成订单编号
        String orderId = UUID.randomUUID().toString().replaceAll("-", "").toUpperCase();


        order.setId(orderId);
        order.setUserId(userId);


        // 测试用，删除 key 以便更新 redis
        redis.delete(RedisCacheManager.getDishStockCacheKey(order.getStoreId()));

        if (order.getConsumeType() == 2) {
            // 以当前时间戳生产取餐码，确保取餐吗唯一
            String code = VerifyUtils.BaseConversion(System.currentTimeMillis(), 32);
            order.setFetchMealCode(code);
        }


        //m 保存的是菜品ID与要下单的数量，主要是将套餐中的每个菜品与单点菜品合并
        Map<Integer, Integer> m = getDishNumMap(order.getDishOrders());
        if (!checkStock(m, order.getStoreId())) {
            throw new OrderAddException("库存不足");
        }
        // 扣减库存
        boolean hasDeduction = false;
        try {
            // 扣减 redis，发送消息，数据库层面会进行乐观锁判断
            for (Map.Entry<Integer, Integer> it : m.entrySet()) {
                int id = it.getKey(), stock = it.getValue();
                redis.opsForHash().increment(RedisCacheManager.getDishStockCacheKey(order.getStoreId()), id, -stock);
            }
            hasDeduction = true;
            // 订单算是生成成功，产生随机支付单号，发起支付
            order.setPayId(UUID.randomUUID().toString().replace("-", ""));
            OrderMessage om = new OrderMessage(m, order);

            System.out.println("扣减库存成功，上锁，发送消息到消息队列！");

            rabbit.convertAndSend(OrderRabbitMqConfig.ORDER_ADD_ROUTEING_KEY, om);

        } catch (NullPointerException e) {
            // 缓存不存在，重试，注意并发条件下会出错，这里不用回滚，因为我们要更新了
            e.printStackTrace();
            redisManager.flushRedisDishStockCache(
                    dishMapper.queryDishInfo(order.getStoreId()),
                    order.getStoreId());
            return addUserOrder(userId, order);
        } catch (Exception e) {
            e.printStackTrace();
            if (hasDeduction) {
                m.put(-1, order.getStoreId());
                rabbit.convertAndSend(OrderRabbitMqConfig.ROLL_BACK_STOCK_ROUTEING_KEY, m);
            }
            throw e;
        }
        // 返回支付单号给前端
        Map map = new HashMap(2);
        map.put("payId", order.getPayId());
        map.put("orderId", order.getId());
        return map;
    }

    /**
     * 处理旧订单
     *
     * @param order
     * @throws OrderAddException
     */
    @Deprecated
    public void handleOldOrder(Order order) throws OrderAddException, ReadWriterLockException {
        // 筛选出添加的菜
        List<Map<String, Object>> dishOrders = order.getDishOrders().stream().filter((item -> {
            return (Boolean) item.getOrDefault("isAdd", false);
        })).collect(Collectors.toList());
        Map<Integer, Integer> m = getDishNumMap(dishOrders);

        order.setDishOrders(dishOrders);

        if (!checkStock(m, order.getStoreId())) {
            throw new OrderAddException("库存不足");
        }
        // 扣减库存
        boolean hasDeduction = false;
        try {
            // 扣减库存
            for (Map.Entry<Integer, Integer> it : m.entrySet()) {
                int id = it.getKey(), stock = it.getValue();
                redis.opsForHash().increment(RedisCacheManager.getDishStockCacheKey(order.getStoreId()), id, -stock);
            }
            hasDeduction = true;
            // 库存扣减成功，订单算完成，发布消息
            order.setOrderType(CONFIRMING_STATUS);
            OrderMessage om = new OrderMessage(m, order);

            rabbit.convertAndSend(OrderRabbitMqConfig.ORDER_ADD_ROUTEING_KEY, om);
        } catch (NullPointerException e) {
            // 缓存不存在，重试
            e.printStackTrace();
            // 该方法是原子的，并且会继续判断缓存是否存在，避免多个线程竞争
            redisManager.flushRedisDishStockCache(dishMapper.queryDishInfo(order.getStoreId()), order.getStoreId());
            handleOldOrder(order);
            return;
        } catch (Exception e) {
            e.printStackTrace();
            if (hasDeduction) {
                m.put(-1, order.getStoreId());
                rabbit.convertAndSend(OrderRabbitMqConfig.ROLL_BACK_STOCK_ROUTEING_KEY, m);
            }
            throw e;
        }


    }

    @Override
    public double getWaitingTime(int storeId) {
        long nowTs = System.currentTimeMillis();
        double waitingTime = 0;
//      将菜品添加至当前菜品队列中，以便计算等待时间，redis中缓存了菜品的制作时间
//      当缓存过期，需要更新缓存
        List queue = redis.opsForList().range(
                RedisCacheManager.DISH_WAITING_QUEUE_KEY, 0, -1);
        try {
            for (Object o : queue) {
                Pair<Integer, Double> pair = (Pair) o;
                int id = pair.getFirst();
                if (id >= 100000) {
                    continue;
                }
                // 菜品下单时间
                double ts = pair.getSecond();
                // * 60000 转换为 时间戳形式 || 菜品制作时间
                double makeTime = (double) redisManager.getForHash(RedisCacheManager.DISH_MAKE_TIME_CACHE_KEY, id) * 60000;

                // 开始时间加制作时间就是结束时间 ts + makeTime
                //暂时没有办法简单的删除队列中的元素
                if (ts + makeTime > nowTs) {
                    waitingTime += (ts + makeTime - nowTs);
                }

            }
        } catch (NullPointerException e) {
            e.printStackTrace();
            redisManager.initRedisDishMakeTimeCache(dishMapper.queryDishes());
            return getWaitingTime(storeId);
        }

        return waitingTime / 60000;
    }

    @Override
    public void deleteOrder(String id) {
        int row = orderMapper.logicalDelete(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(OrderApplyTable at) {
        try {
            int row = orderMapper.insertCancelApply(at);
        } catch (Exception e) {
            e.printStackTrace();
            orderMapper.deleteCancelApply(at.getOrderId());
            orderMapper.insertCancelApply(at);
        }
        orderMapper.updateOrderType(at.getOrderId(), 8);
    }


    @Override
    public boolean isOccupied(int sid, int tid, String uid) {
        if (tid == -1) {
            return false;
        }
        String occupiedUser = null;
        for (Map.Entry<String, ShoppingCart> it : cartCache.entrySet()) {
            ShoppingCart v = it.getValue();
            if (v.getStoreId() == sid && v.getTableId() == tid) {
                occupiedUser = it.getKey();
                break;
            }
        }

        if (occupiedUser != null && !occupiedUser.equals(uid)) {
            // 如果确实有人占领桌位，查看是否锁定并且是否是别人占领
            synchronized (this) {
                ShoppingCart cart = cartCache.get(occupiedUser);
                long interval = System.currentTimeMillis() - cart.getLastModify();
                long minute = interval / (1000 * 60);
                // 已经 1 小时没编辑了，自动释放
                if (minute >= 60) {
                    relieveOccupied(occupiedUser);
                    return false;
                }
                if (cart != null) {
                    return cart.isLock() && !occupiedUser.equals(uid);
                }
            }
        }
        return false;
    }

    @Override
    public void relieveOccupied(String uid) {
        cartCache.remove(uid);
    }



    /**
     * 向购物车内添加菜品
     *
     * @param sid 店铺 ID
     * @param tid 桌号，当不是扫码点餐时为 -1
     * @return
     */
    @Override
    public ShoppingCart addDish(int sid, int tid, String uid, Dish dish) throws OrderAddException {
        int did = dish.getId();
        ShoppingCart cart = cartCache.get(uid);
        // 如果当前购物车为空，说明用户第一次进入，如果是桌位点餐，必须锁定桌位
        if (cart == null) {
            if (tid != -1) {
                synchronized (this) {
                    if (cartCache.get(uid) == null) {
                        cartCache.put(uid, new ShoppingCart());
                        // 锁定桌位
                        cartCache.get(uid).setLock(true);
                    } else {
                        throw new OrderAddException("当前桌位已被占有");
                    }
                }
            } else {
                // 如果不需要锁定桌位，那么不需要上锁，但是仍然考虑并发问题
                // 如果在这一瞬间另一个线程已经 put 了，那么这里就不需要重复 put
                cartCache.putIfAbsent(uid, new ShoppingCart());
            }
            cart = cartCache.get(uid);
            cart.setStoreId(sid);
            cart.setTableId(tid);
            cart.setUserId(uid);
            cart.setComplete(false);
        }

        cart.setLastModify(System.currentTimeMillis());
        Map<String, ShoppingCart.DishOrder> dishOrders = null;
        // 对于每个购物车，我们希望执行顺序是串行化的
        // 否则如果用户一瞬间先加后减，由于并发问题，反应在用户眼前的可能就是先减后加
        // 虽然结果可能是一致的，但对用户而言体验是不好的
        synchronized (cart) {
            dishOrders = cart.isComplete() ?
                    cart.getNewDishOrders() : cart.getDishOrders();

            dishOrders.putIfAbsent(String.valueOf(did), new ShoppingCart.DishOrder());
            ShoppingCart.DishOrder dishOrder = dishOrders.get(String.valueOf(did));
            // 总的折扣使用数目
            int totalNum = dish.getDiscount().getCount();
            // 能够使用的折扣数目 = 总的折扣使用数目 - 用户之前已使用的 - 本次购物已使用的
            int restNum = totalNum - getUsedDiscountNum(did, uid, cart, true);
            if (restNum > 0 && !super.idCombo(did)) {
                // 如果还有折扣数目可用，那么就使用折扣数目
                double discount = dish.getDiscount().getDiscount(dish.getPrice());
                dishOrder.increaseDiscountUsedCount();
                cart.setDiscount(cart.getDiscount() + discount);
            } else if (super.idCombo(did)) {
                // 如果是套餐的话，那么没有使用限制
                double discount = dish.getDiscount().getDiscount(dish.getPrice());
                if (discount > 0) {
                    // 这就说明有优惠
                    cart.setDiscount(cart.getDiscount() + discount);
                }
            }
            dishOrder.setPrice(dish.getPrice());
            dishOrder.setName(dish.getName());
            dishOrder.increaseDishNum();
            cart.setTotalPrice(cart.getTotalPrice() + dish.getPrice());
        }
        return cart;
    }

    /**
     * 获取用户已使用的折扣数目
     *
     * @param did
     * @param uid
     * @param cart
     * @param addBefore 如果为 false，表面只返回本次购物使用的次数，否则，将会加上用户之前已经使用的次数
     * @return
     */
    private int getUsedDiscountNum(int did, String uid, ShoppingCart cart, boolean addBefore) {
        // 本次购物使用的
        int totalDiscountUsedNum = cart.getDishOrders().getOrDefault(String.valueOf(did), new ShoppingCart.DishOrder()).getDiscountUsedCount() +
                cart.getNewDishOrders().getOrDefault(String.valueOf(did), new ShoppingCart.DishOrder()).getDiscountUsedCount();
        // 如果不需要计算用户之前使用数目，那么直接返回
        if (!addBefore) {
            return totalDiscountUsedNum;
        }
        // 用户之前已经使用的次数，这里查询走 mybatis 缓存
        Map map = userMapper.queryUserUsedDiscountCountInAllDish(uid).get(did);
        if (map == null) {
            return totalDiscountUsedNum;
        }
        return totalDiscountUsedNum + (int) map.getOrDefault("count", 0);
    }

    private int getDishNum(int did, ShoppingCart cart) {
        return cart.getDishOrders().getOrDefault(String.valueOf(did), new ShoppingCart.DishOrder()).getNum() +
                cart.getNewDishOrders().getOrDefault(String.valueOf(did), new ShoppingCart.DishOrder()).getNum();
    }


    @Override
    public ShoppingCart removeDish(int sid, int tid, String uid, Dish dish) {
        ShoppingCart cart = cartCache.get(uid);
        if (cart == null) {
            return null;
        }
        int did = dish.getId();
        if (cart.isComplete()) {
            Map<String, ShoppingCart.DishOrder> newDish = cart.getNewDishOrders();
            if (newDish.getOrDefault(String.valueOf(did), new ShoppingCart.DishOrder()).getNum() <= 0) {
                // 如果新加的菜中没有这个菜，那么说明用户正尝试减少一个已点的菜品
                // 当用户正在加菜时，我们不允许用户减菜，因为菜肴可能下锅，因此返回特殊值告知用户
                cart.setLastModify(-1);
                return cart;
            }
        }
        cart.setLastModify(System.currentTimeMillis());
        Map<String, ShoppingCart.DishOrder> dishOrders = null;
        synchronized (cart) {
            dishOrders = cart.isComplete() ? cart.getNewDishOrders() : cart.getDishOrders();
            dishOrders.putIfAbsent(String.valueOf(did), new ShoppingCart.DishOrder());
            ShoppingCart.DishOrder dishOrder = dishOrders.get(String.valueOf(did));
            // 本次购物已使用的折扣数目（仅本次，不包括以前的）
            int discountUsedNum = getUsedDiscountNum(did, null, cart, false);
            // 总的折扣使用数目
            int totalNum = dish.getDiscount().getCount();
            // 点单前可享受的折扣数目 = 总数目 - 用户之前已经使用的次数 = 总数目 - 之前已经使用的数目 - 本次使用的数目 + 本次使用的数目
            int freeNum = totalNum - getUsedDiscountNum(did, uid, cart, true) + discountUsedNum;
            // 用户当前已点的菜品数目
            int dishNum = getDishNum(did, cart);
            /**
             *  如果使用了折扣的话，那么减菜时也要有对应的逻辑处理，考虑两种通用的清空：
             *  1、用户可享受3次优惠，但是用户点菜数目为5，只有前三次有优惠，那么移除第五个不会涉及优惠金额
             *  2、但是如果用户点菜数目为 3，移除第三个必须增加用户可享受次数，然后减少对用户的优惠价格
             *  综合来看，只有已点数目小于等于可享受的数目，才需要处理优惠金额
             */
            if (freeNum >= dishNum && !super.idCombo(did)) {
                double discount = dish.getDiscount().getDiscount(dish.getPrice());
                // 无所谓减 dishOrder 还是 newDishOrder，因为计算时都是计算二者相加
                dishOrder.decreaseDiscountUsedCount();
                cart.setDiscount(cart.getDiscount() - discount);
            } else if (super.idCombo(did)) {
                // 如果是套餐的话，那么没有使用限制
                double discount = dish.getDiscount().getDiscount(dish.getPrice());
                if (discount > 0) {
                    // 这就说明有优惠
                    cart.setDiscount(cart.getDiscount() - discount);
                }
            }
            dishOrder.decreaseDishNum();
            cart.setTotalPrice(cart.getTotalPrice() - dish.getPrice());
            return cart;
        }
    }

    @Override
    public ShoppingCart getCartFromOrder(String oid) {
        Order order = orderMapper.queryOrder(oid);
        System.out.println("order = " + order);
        if (order == null) {
            return null;
        }
        ShoppingCart cart = new ShoppingCart();
        cart.setComplete(true);
        cart.setUserId(order.getUserId());
        cart.setStoreId(order.getStoreId());
        cart.setTableId(order.getTable());
        cart.setConsumeType(order.getConsumeType());
        cart.setLock(true);
        cart.setTotalPrice(order.getOriginalPrice());
        cart.setDiscount(order.getShopDiscount());
        cart.setOrderId(order.getId());
        Map<String, ShoppingCart.DishOrder> dishOrders = cart.getDishOrders();

        for (Map<String, Object> map : order.getDishOrders()) {
            System.out.println("map = " + map);
            ShoppingCart.DishOrder dishOrder = new ShoppingCart.DishOrder();
            dishOrder.setId((Integer) map.get("dishId"));
            dishOrder.setNum((Integer) map.get("dishNum"));
            dishOrder.setPrice((Double) map.get("dishPrice"));
            dishOrder.setDiscountUsedCount((Integer) map.getOrDefault("usedCount", 0));
            dishOrder.setName((String) map.get("dishName"));

            dishOrders.put(String.valueOf(dishOrder.getId()), dishOrder);
        }

        cartCache.put(order.getUserId(), cart);
        return cart;
    }

    @Override
    public int isComplete(String orderId) {
        return content.isComplete(orderId);
    }

    @Override
    public ShoppingCart getCart(String uid) {
        ShoppingCart cart = null;
        for (Map.Entry<String, ShoppingCart> it : cartCache.entrySet()) {
            if (it.getKey().equals(uid)) {
                return it.getValue();
            }
        }
        return null;
    }
//
//    /**
//     * 订单添加后续消费者，分布式情况下应该由多个消费者
//     *
//     * @param bytes
//     * @param m
//     * @param channel
//     * @throws Exception
//     */
//    @RabbitListener(queues = {OrderRabbitMqConfig.ORDER_ADD_QUEUE})
//    public void doAddOrder(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws Exception {
//        channel.basicQos(1);
//        OrderMessage om = null;
//        try {
//            om = (OrderMessage) JsonUtils.getObjectFromBytes(bytes);
//        } catch (Exception e) {
//            e.printStackTrace();
//            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//        }
//        // 防止消费, messageId 是UUID产生的，比较长，bitmap 塞不下
//        if (!VerifyUtils.isNullOrEmpty((String) redis.opsForValue().get(RedisCacheManager.getOrderMessageCacheKey(om.getMessageId())))) {
//            return;
//        }
//
//        com.happysnaker.pojo.Order order = om.getOrder();
//        try {
//            // 先扣减库存，看看能不能通过乐观锁
//            addToWaitingAndDeductionInventoryQueue(om.getDishNumMap(), order.getStoreId());
//
//            if (order.getIsNew()) {
//                orderMapper.insertOrderInfo(order);
//                orderMapper.insertOrderPay(order.getId(), order.getPayId());
//            } else {
//                // 旧订单价格可能改变，更新价格，并且让管理员重新确认，更新订单状态
//                orderMapper.updateShopDiscount(order.getId(), order.getShopDiscount());
//                orderMapper.updateShopOriginalPrice(order.getId(), order.getOriginalPrice());
//                this.updateOrderType(order.getId(), CONFIRMING_STATUS);
//            }
//            //取餐凭证
//            if (order.getConsumeType() == 2) {
//                orderMapper.insertFetchMealCode(order.getId(), order.getFetchMealCode());
//            }
//
//            handleDishOrders(order.getId(), order.getUserId(), order.getStoreId(), order.getDishOrders());
//
//            // 更新积分
//            userMapper.updateUserPoints(order.getUserId(), integral);
//            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//        } catch (Exception e) {
//            e.printStackTrace();
//            // 第三个参数 false，拒绝重新入队，那么这条消息将进入死信
//            channel.basicNack(m.getMessageProperties().getDeliveryTag(), false, false);
//            // 抛出异常，事务回滚
//            throw e;
//
//        } finally {
//            redis.opsForValue().set(RedisCacheManager.getOrderMessageCacheKey(om.getMessageId()), "ok");
//            redis.expire(RedisCacheManager.getOrderMessageCacheKey(om.getMessageId()), 60, TimeUnit.SECONDS);
//        }
//    }
//
//    /**
//     * 更新用户使用折扣、插入订单菜品表、更新销量
//     */
//    public void handleDishOrders(String orderId, String userId, int storeId, List<Map<String, Object>> dishOrders) throws IOException {
//        for (var it : dishOrders) {
//            int row = userMapper.updateUsedDiscountCount(
//                    userId, (int) it.get("dishId"), (int) it.get("usedCount"));
//            if (row == 0) {
//                userMapper.insertUsedDiscountCount(
//                        userId,
//                        (int) it.get("dishId"), (int) it.get("usedCount"));
//            }
//            orderMapper.insertOrderDish(orderId, it);
//            dishMapper.updateDishSale((int) it.get("dishId"), (int) it.get("dishNum"));
//            //在 Redis 中存储当日的销量，由定时任务在每日 0点 写入
//            redis.opsForHash().increment(RedisCacheManager.getTodayDateKey(), storeId, (int) it.get("dishNum"));
//        }
//    }
//
//    /**
//     * 将菜品添加至等待队列，并扣减库存
//     */
//    public void addToWaitingAndDeductionInventoryQueue(Map<Integer, Integer> DishNumMap, int storeId) throws IOException, OrderAddException {
//        long nowTs = System.currentTimeMillis();
//        for (var it : DishNumMap.entrySet()) {
//            System.out.println("扣减订单" + it.getKey() + " stock == " + it.getValue());
//            // 乐观锁查询
//            int row = dishMapper.updateDishInventory(storeId, it.getKey(), -it.getValue());
//            System.out.println("扣减完成" + row);
//            if (row == 0) {
//                // 不捕获，让事务回滚
//                throw new OrderAddException("库存不足");
//            }
//            //将菜品添加至等待队列中，对于多个相同菜品，重复添加单独实例以便处理
//            for (int i = 0; i < (int) it.getValue(); i++) {
//                redis.opsForList().leftPush(
//                        RedisCacheManager.DISH_WAITING_QUEUE_KEY,
//                        new Pair<Integer, Double>((Integer) it.getKey(), (double) nowTs));
//            }
//        }
//    }
//
//
//    /**
//     * 订单库存回滚
//     * @param bytes
//     * @param m
//     * @param channel
//     * @throws Exception
//     */
//    @RabbitListener(queues = {OrderRabbitMqConfig.ROLL_BACK_STOCK_QUEUE})
//    public void rollBackStock(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws Exception {
//        Map<Integer, Integer> DishNumMap = (Map<Integer, Integer>) JsonUtils.getObjectFromBytes(bytes);
//        int storeId = DishNumMap.get(-1);
//        try {
//            for (var it : DishNumMap.entrySet()) {
//                int id = it.getKey(), stock = it.getValue();
//                redis.opsForHash().increment(RedisCacheManager.getDishStockCacheKey(storeId), id, stock);
//            }
//            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//        } catch (IOException e) {
//            e.printStackTrace();
//            channel.basicNack(m.getMessageProperties().getDeliveryTag(),
//                    false, false);
//            throw e;
//        } catch (NullPointerException e) {
//            redisManager.initRedisDishStockCache(dishMapper.queryDishInfo(storeId), storeId);
//            rollBackStock(bytes, m, channel);
//            throw e;
//        }
//    }
//
//    @RabbitListener(queues = {OrderRabbitMqConfig.ORDER_ADD_DEAD_QUEUE})
//    public void deadOrderMessageHandler(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws IOException {
//        channel.basicQos(1);
//        OrderMessage om = null;
//        try {
//            om = (OrderMessage) JsonUtils.getObjectFromBytes(bytes);
//        } catch (Exception e) {
//            e.printStackTrace();
//            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//        }
//        //取消订单
//        orderMapper.updateOrderType(om.getOrder().getId(), 9);
//        com.happysnaker.pojo.Message message = com.happysnaker.pojo.Message.createSystemMessage("通知，您的订单处理失败", "服务器发送了一些不好的事情，因此没能正确处理您的订单，十分抱歉，您可以前往 我的-客服 寻求退款，订单ID为唯一凭证。订单ID：" + om.getOrder().getId(), om.getOrder().getUserId());
//        messageMapper.insertMessage(message);
//        if (messageMapper.updateUnReadUserMsgCount(message.getUserId(), 1) == 0) {
//            messageMapper.insertUnReadUserMsgCount(message.getUserId(), 1);
//        }
//
//        // 发送消息回滚 redis
//        om.getDishNumMap().put(-1, om.getOrder().getStoreId());
//        rabbit.convertAndSend(OrderRabbitMqConfig.ROLL_BACK_STOCK_ROUTEING_KEY, om.getDishNumMap());
//        channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//    }
//
//
//    /**
//     * 死信队列，尝试取消订单
//     *
//     * @param m
//     * @param channel
//     * @throws IOException
//     */
//    @RabbitListener(queues = {OrderRabbitMqConfig.ORDER_CANCEL_QUEUE})
//    public void doCancelOrder(byte[] bytes, Message m, com.rabbitmq.client.Channel channel) throws IOException {
//        channel.basicQos(1);
//        System.out.println("开始删除订单!!!");
//        OrderMessage om = null;
//        try {
//            om = (OrderMessage) JsonUtils.getObjectFromBytes(bytes);
//        } catch (Exception e) {
//            e.printStackTrace();
//            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//        }
//        String orderId = om.getOrder().getId();
//        Order nowOrder = orderMapper.queryOrder(orderId);
//        // 如果订单状态与数据库中不相等，说明已经被消费过了，或者用户支付了订单，直接确认即可
//        if (nowOrder.getOrderType() != om.getOrder().getOrderType()) {
//            channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//            return;
//        }
//
//        // 发送消息回滚 redis
//        Map<Integer, Integer> m1 = getDishNumMap(om.getOrder().getDishOrders());
//        m1.put(-1, om.getOrder().getStoreId());
//        rabbit.convertAndSend(OrderRabbitMqConfig.ROLL_BACK_STOCK_ROUTEING_KEY, m1);
//
//        // 取消订单
//        orderMapper.updateOrderType(om.getOrder().getId(), CANCELLED_STATUS);
//
//        com.happysnaker.pojo.Message message = com.happysnaker.pojo.Message.createSystemMessage("订单取消通知", "您有一份订单由于超时未支付而取消，订单ID为 " + om.getOrder().getId(), om.getOrder().getUserId());
//        messageMapper.insertMessage(message);
//        if (messageMapper.updateUnReadUserMsgCount(message.getUserId(), 1) == 0) {
//            messageMapper.insertUnReadUserMsgCount(message.getUserId(), 1);
//        }
//        channel.basicAck(m.getMessageProperties().getDeliveryTag(), false);
//    }
}


