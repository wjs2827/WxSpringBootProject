package com.happysnaker.service;

import org.springframework.amqp.core.Message;

/**
 * @author Happysnaker
 * @description
 * @date 2022/3/14
 * @email happysnaker@foxmail.com
 */
public interface OrderConsumer {
    /**
     * <P>当订单判断库存足够时，此时可由队列消费者完成后续任务，使用消费者的好处在于不用使客户堵塞等待，并且可以设置队列长度，实现限流，订单消费者需要实现的功能：</P>
     * <ul>
     *     <li>根据参数 deductInventory 决定是否需要扣减库存</li>
     *     <li>一旦库存扣减完成，消费者开始将订单写入订单表，如果必要，更新订单状态或者价格等信息</li>
     *     <li>将新增的菜品写入等待队列，以便计算等待时间</li>
     *     <li>更新用户已使用的折扣数目</li>
     *     <li>更新用户积分</li>
     *     <li>更新菜品销量</li>
     *     <li>向 Redis 中写入结果，告知用户</li>
     * </ul>
     * <p><strong>注意此方法必须是原子的，要么执行成功，要么什么都不做，无论成功还是失败，都应该告知用户结果</strong></p>
     * @param bytes 消息队列传递的序列化的消息，可调用{@link com.happysnaker.utils.JsonUtils#getObjectFromBytes(byte[])} 方法转换成 {@link com.happysnaker.pojo.OrderMessage}
     * @param msg 消息队列中标识消息的属性
     * @param channel 消息队列中传递消息的通道
     * @throws Exception
     */
    void doAddOrder(byte[] bytes, Message msg, com.rabbitmq.client.Channel channel) throws Exception;
}
