// app.js
import Dialog from '/dist/dialog/dialog';
App({

	// 登陆时逻辑，获取缓存 token，获取失败说明登录失效
	onLaunch() {
		wx.getStorage({
			key: 'token',
			success(res) {
				console.log(res.data)
			},
			fail: (err) => {
				console.log("获取 oppenid 缓存失败，未登录");
				this.login();
			}
		})
	},

	//--------------菜品相关 API ------------------

	getUserLikeAndCollectedDish(success, fail) {
		this.getRequest("/get_user_marked_dish", {
			
		}, success, fail)
	},
	

	/**
	 * 获取菜品喜欢数目变更情况，用 dish.likeNum + 此接口返回的变更数目 即为菜品最终的喜欢数目
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getDishLikeNumList(success, fail) {
		this.getRequest("/get_dish_like_num", {
			
		}, success, fail)
	},

	/**
	 * 添加或移除用户喜欢的菜品
	 * @param {number} option 1代表添加， 0代表移除
	 */
	addOrRemoveUserLikeDish(dishId, option, success) {
		this.checkLoginStatus();
		let path = option == 0 ? "/remove_user_like_dish" : "/add_user_like_dish";
		this.postRequest(path, {	
				dishId: dishId
			}, success,
			(err) => {
				//参数错误，很有可能是 
				this.dialog(this, "服务错误", "网络或服务器出错，无法添加或移除喜欢");
			})
	},


	/**
	 * 添加或移除用户待选的菜品
	 * @param {number} option 1代表添加， 0代表移除
	 */
	addOrRemoveUserWillBuyDish(dishId, option, success) {
		this.checkLoginStatus();
		let path = option == 0 ? "/remove_user_will_buy_dish" : "/add_user_will_buy_dish";
		this.postRequest(path, {
				
				dishId: dishId
			}, success,
			(err) => {
				//参数错误，很有可能是 
				this.dialog(this, "服务错误", "网络或服务器出错，无法添加或移除喜欢");
			})
	},


	/**
	 * 添加或移除用户收藏的菜品
	 * @param {number} option 1代表添加， 0代表移除
	 */
	addOrRemoveUserCollectedDish(dishId, option, success) {
		this.checkLoginStatus();
		let path = option == 0 ? "/remove_user_collected_dish" : "/add_user_collected_dish";
		this.postRequest(path, {
				
				dishId: dishId
			}, success,
			(err) => {
				//参数错误，很有可能是 
				this.dialog(this, "服务错误", "网络或服务器出错，无法添加或移除喜欢");
			})
	},

	/**
	 * 检查订单
	 * @param {string} orderId 
	 * @param {function} success 订单出结果时执行的逻辑，注意可能成功也可能失败
	 * @param {function} fail 当网络调用失败时的逻辑
	 * @param {function} waiting  当订单正在处理中的逻辑
	 * @param count 轮询次数
	 */
	check(orderId, success, fail, waiting, count) {
		let c = (orderId, success, fail, waiting, count)=> {
			if (count == undefined) count = 10;
			this.doCheck(orderId, success, fail, waiting, count);
		}
		setTimeout(function() {
			c(orderId, success, fail, waiting, count);
		}, 1000);
	},

	doCheck(orderId, success, fail, waiting, count) {
		if (count != 0) {
			console.log("第" + count + "次轮询结果");
			this.getRequest("/check", {
				orderId: orderId
			}, (res)=> {
				let code = res.data.code;
				if (code == 0) {
					waiting(res);
					this.check(orderId, success, fail, waiting, count - 1)
				} else {
					success(res);
				}
			}, (err)=> {
				this.check(orderId, success, fail, waiting, count - 1)
			})

		} else {
			fail();
		}
	},


	/**
	 * 获取用户对于某个促销菜品已使用的优惠次数
	 */
	getUserUsedDiscountNum(success, fail) {
		this.getRequest("/get_used_discount_num", {
			userId: wx.getStorageSync('openid')
		}, success, fail)
	},


	/**
	 * 获取菜品的分类
	 */
	getDishClassification(success, fail) {
		this.getRequest("/get_dish_classification", null, success, fail)
	},

	/**
	 * useCache 代表是否使用缓存(此字段已弃用)
	 *  获取首页的菜品信息
	 * 返回值为 res.hotDishList 热销榜单
	 * res.recommendDishList 推荐
	 * res.newDishList 新品
	 * res.combos 套餐
	 * @param obj 由于此方法为主页的加载方法，当未登陆时，将尝试登录，登录成功后将调用 obj 的刷新方法进行刷新
	 */
	getIndexDishInfo(useCache, success, fail, loginComplete) {
		this.getRequest("/get_index_dish_info", {
			cache: useCache
		}, success, fail, loginComplete);
	},


	/**
	 * 获取点餐页面的菜品信息，这会包括库存、折扣等其他信息
	 * @param {*} storeId 店铺 ID
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getOrderDishInfo(storeId, success, fail) {
		this.getRequest("/get_order_dish_info", {
			storeId: storeId,
			
		}, success, fail);
	},

	/**
	 * 获取用户需要等待的时间，这只是一个预估的时间
	 * @param {*} storeId 
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getWatingTime(storeId, success, fail) {
		this.getRequest("/get_waiting_time", {
			storeId: storeId
		}, success, fail);
	},

	/**
	 * 该API会返回用户收藏的具体的菜品与套餐数据，而不仅仅收藏ID列表
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getUserCollectedDishes(success, fail) {
		this.getRequest("/get_user_collected_dishes", {
		}, success, fail)
	},

	/**
	 * 获取店铺信息列表
	 * @param  useCache 已弃用
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getStore(useCache, success, fail) {
		this.getRequest("/get_store_list", {
			cache: useCache
		}, success, fail);
	},

	/**
	 * 通过店铺 ID 获取店铺信息
	 * @param {*} id 
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getStoreById(id, success, fail) {
		this.getRequest("/get_store", {
			storeId: id
		}, success, fail);
	},


	/**
	 * 获取用户收藏的店铺 ID 列表
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getUserCollectedStore(success, fail) {
		this.getRequest("/get_collected_stores", {
			
		}, success, fail)
	},

	/**
	 * 添加或移除用户喜欢店铺
	 * @param {*} storeId  店铺 ID
	 * @param {*} option  0 移除，1 添加
	 * @param {*} success 
	 */
	addOrRemoveUserCollectedStore(storeId, option, success) {
		this.checkLoginStatus();
		let path = option == 0 ? "/remove_user_collected_store" : "/add_user_collected_store";
		this.postRequest(path, {
				
				storeId: storeId
			}, success,
			(err) => {
				//参数错误，很有可能是 
				this.dialog(this, "服务错误", "网络或服务器出错，无法添加或移除喜欢");
			})
	},

	/**
	 * 查询桌位是否被占有
	 * @param {*} sid 店铺 ID
	 * @param {*} tid 桌位号
	 * @param {*} success 
	 * @param {*} fail 
	 */
	queryIsOccupied(sid, tid, success, fail) {
		this.getRequest("/is_occupied", {
			sid: sid,
			tid: tid
		}, success, fail);
	},

	relieveOccupied(sid, tid, consumeType, success, fail) {
		this.postRequest("/relieve_occupied", {
			sid: sid,
			tid: tid,
			consumeType: consumeType
		}, success, fail)
	},

	/**
	 * 添加菜品到购物车中
	 * @param {*}} order 
	 * @param {*} success 
	 * @param {*} fail 
	 */
	addDish(dish, sid, tid, consumeType, success, fail) {
		this.postRequest("/add_dish", {
			dish: dish,
			sid: sid,
			tid: tid,
			consumeType: consumeType
		}, success, fail)
	},

	/**
	 * 将菜品从购物车中移除
	 * @param {*}} order 
	 * @param {*} success 
	 * @param {*} fail 
	 */
	removeDish(dish, sid, tid, success, fail) {
		this.postRequest("/remove_dish", {
			dish: dish,
			sid: sid,
			tid: tid
		}, success, fail)
	},
	

	/**
	 * 获取购物车，用于加餐使用
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getCart(orderId, success, fail) {
		this.getRequest("/get_cart", {
			orderId: orderId
		}, success, fail)
	},

	/**
	 * 获取订单，进入结算
	 * @param {*} success 
	 * @param {*} fail 
	 */
	getOrder(success, fail) {
		this.getRequest("/get_order", {
		}, success, fail)
	},


	/**
	 * 添加订单
	 * @param {*}} order 
	 * @param {*} success 
	 * @param {*} fail 
	 */
	addOrder(order, success, fail) {
		console.log("addOrder");
		this.postRequest("/add_user_order", {
			order: order,
			
		}, success, fail)
	},


	/**
	 * 取消支付
	 * @param {*} oid 订单 ID
	 * @param {*} success 
	 * @param {*} fail 
	 */
	cancelPay(oid, success, fail) {
		this.postRequest("/cancelpay", {
			orderId: oid,
		}, success, fail)
	},

	/**
	 * 支付
	 * @param {*} pid  pay ID，支付流水号
	 * @param {*} success 
	 * @param {*} fail 
	 */
	pay(pid, success, fail) {
		this.postRequest("/pay", {
			payId: pid,
		}, success, fail)
	},

	/**
	 * 取消订单
	 * @param {*} data 描述信息
	 * @param {*} success 
	 * @param {*} fail 
	 */
	cancelOrder(data, success, fail) {
		this.postRequest("/cancel_order", {
			apply: data
		}, success, fail)
	},

	// 删除订单
	deleteOrder(orderId, success, fail) {
		this.postRequest("/delete_order", {
			orderId: orderId
		}, success, fail)
	},

	// 获取订单列表
	getUserOrders(success, fail) {
		this.getRequest("/get_user_orders", {
			
		}, success, fail)
	},

	// 获取用户地址列表
	getUserAddr(success, fail) {
		this.getRequest("/get_user_address", {
			
		}, success, fail)
	},

	// 添加地址
	addUserAddr(data, success, fail) {
		this.postRequest("/add_user_address", {
			address: data
		}, success, fail)
	},

	// 更新地址
	updateUserAddr(data, success, fail) {
		this.postRequest("/update_user_address", {
			address: data,
		}, success, fail)
	},

	// 删除地址
	removeUserAddr(addrId, success, fail) {
		this.postRequest("/remove_user_address", {
			addressId: addrId,
		}, success, fail)
	},

	// 获取用户信息
	getUserInfo(success, fail) {
		this.getRequest("/get_user_info", {
			
		}, success, fail);
	},

	// 获取消息列表
	getUserMessage(success, fail) {
		this.getRequest("/get_user_message", {
			
		}, success, fail);
	},

	// 未读消息数目
	getUserMessageCount(success, fail) {
		this.getRequest("/get_user_message_count", {
			timestamp: (new Date()).valueOf()
		}, success, fail);
	},



	dialog(obj, title, content, close) {
		Dialog.alert({
			conent: obj,
			title: title,
			message: content,
		}).then(() => {
			// on close
			close();
		});
	},

	errDialog(obj) {
		this.dialog(obj, "发送了意料之外的错误", "请刷新重试，如果此错误仍然存在，请点击 我的 客服 及时与我们反应")
	},

	dialogWithAction(obj, title, content, beforeClose) {
		Dialog.confirm({
			conent: obj,
			title: title,
			message: content,
			beforeClose
		})
	},

	//POST需要额外封装请求头 "application/x-www-form-urlencoded"，拦截设置 token
	postRequest(path, data, success, fail, loginComplete) {
		var reqTask = wx.request({
			url: this.globalData.url + path,
			data: data,
			header: {
				"Content-Type": "application/x-www-form-urlencoded",
				"Authorization": wx.getStorageSync('token')
			},
			method: 'POST',
			dataType: 'json',
			responseType: 'text',
			success: (result) => {
				console.log(result);
				// 401 身份过期
				if (result.statusCode == 401) {
					wx.hideLoading();
					this.dialogWithAction(this, "您暂未登录", "点击确定尝试重新登陆",
						(action) => new Promise((resolve) => {
							if (action === 'confirm') {
								this.login(loginComplete);
								resolve(true);
							} else {
								resolve(true);
							}
						})
					)
					return;
				}
				if (result.statusCode != 200) {
					fail(result.statusCode);
				} else {
					success(result);
				}
			},
			fail: (err) => {
				console.log(err);
				fail(err)
			},
			complete: () => {}
		});

	},

	// GET 请求，拦截设置 token
	getRequest(path, data, success, fail, loginComplete) {
		console.log(success);
		var reqTask = wx.request({
			url: this.globalData.url + path,
			data: data,
			header: {
				"Authorization": wx.getStorageSync('token')
			},
			method: 'GET',
			dataType: 'json',
			responseType: 'text',
			success: (result) => {
				if (result.statusCode == 401) {
					wx.hideLoading();
					this.dialogWithAction(this, "您暂未登录", "点击确定尝试重新登陆并刷新页面重试",
						(action) => new Promise((resolve) => {
							if (action === 'confirm') {
								this.login(loginComplete);
								resolve(true);
							} else {
								resolve(true);
							}
						})
					)
					return;
				}
				if (result.statusCode != 200) {
					fail(result.statusCode);
				} else {
					success(result);
				}
			},
			fail: (err) => {
				console.log("errrwqw" + err);
				fail(err)
			},
			complete: () => {}
		});

	},

	// 检查登录
	checkLoginStatus() {
		let openid = wx.getStorageSync('openid');
		if (!openid) {
			this.login();
		}
	},

	// 登录,loginComplete 表示登陆成功后将调用的方法
	login(loginComplete) {
		wx.login({
			success: (res) => { //请求自己后台获取用户 openid
				let that = this;
				this.postRequest("/login", {
						jsCode: res.code
					},
					(response) => {
						console.log(response);
						var openid = response.data.openid;
						var token = response.data.token;
						console.log("userData", response.data); //可以把openid存到本地，方便以后调用
						wx.setStorageSync('openid', openid);
						wx.setStorageSync('token', token);
						this.dialog(this, "登陆成功", "您已成功登录");
						if (loginComplete) {
							loginComplete();
						}
					},
					(err) => {
						wx.removeStorageSync('openid')
						this.dialog(this, "未知错误", "登录未成功，请您退出小程序重试，如果此错误持续存在，请联系我们 邮箱：happysnaker@foxmail.com")
					},
					(c)=> {
						console.log("ccccccccc");
					}
				)

			}
		})
	},

	globalData: {
		userInfo: null,
		// url: "http://192.168.43.235:8088"
		// url: "http://f393281q73.zicp.vip:80"
		url: "https://happysnaker.xyz:8088"
	}
})