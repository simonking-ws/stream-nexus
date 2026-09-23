package com.simonking.nexus.websocket.model;

import io.netty.channel.Channel;
import lombok.Data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 接入连接（业务系统侧的客户端）
 *
 * <p>与终端连接 {@link WsClient} 是两种东西：这里一条连接背后是一整个业务系统，
 * 数量个位数到几十，但推的量很大，因此台账必须能看到「连了多久、推了多少条」。
 *
 * <p>{@code lastActiveTime} / {@code msgCount} 由 Netty EventLoop 线程写、HTTP 线程读，
 * 因此用 {@code volatile} 与 {@code AtomicLong}——不要为了省事换成普通字段。
 *
 * @author simonking
 */
@Data
public class TcpClient {

    /**
     * 通道ID：TCP 连接没有客户端ID（业务系统不订阅模块、也不需要被定向），只能用通道ID 标识
     */
    private final String channelId;

    private final Channel channel;

    /**
     * 对端IP（已归一化）
     */
    private final String ip;

    private final long createTime;

    private volatile long lastActiveTime;

    /**
     * 最近一次收到上行数据的时间：读空闲超时判定的依据
     */
    private volatile long lastPongTime;

    /**
     * 上行报文计数
     */
    private final AtomicLong msgCount = new AtomicLong();

    public TcpClient(String channelId, Channel channel, String ip) {
        this.channelId = channelId;
        this.channel = channel;
        this.ip = ip;
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = this.createTime;
        this.lastPongTime = this.createTime;
    }
}
