package com.simonking.nexus.websocket.model;

import io.netty.channel.Channel;
import lombok.Data;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条 WebSocket 连接
 *
 * <p>{@code lastPongTime} 用 volatile：心跳由 Netty 的 EventLoop 线程更新，
 * 而管理界面 / 推送请求在业务线程读取，必须保证跨线程可见。
 *
 * @author simonking
 */
@Data
public class WsClient {

    /**
     * 客户端ID：终端建连时自带（推荐自己生成 UUID 并持久化），不带则由服务端生成后随回执下发。
     * 它是注册表主键，也是业务系统做定向推送的寻址依据
     */
    private final String clientId;

    /**
     * Netty 通道。推送的本质就是往它 write 一个 TextWebSocketFrame
     */
    private final Channel channel;

    /**
     * 订阅的模块集合
     */
    private final Set<String> modules;

    /**
     * 客户端IP
     */
    private final String ip;

    /**
     * 握手 URI（原样保留，便于排障）
     */
    private final String uri;

    /**
     * 建连时间（毫秒）
     */
    private final long createTime;

    /**
     * 最近一次心跳应答时间。初始值取建连时间，避免刚连上就被判失联
     */
    private volatile long lastPongTime;

    /**
     * 最近一次收到任何上行数据的时间
     */
    private volatile long lastActiveTime;

    /**
     * 上行消息数（客户端 -> 服务端）
     */
    private final AtomicLong uplinkCount = new AtomicLong();

    /**
     * 下行消息数（服务端 -> 客户端）
     */
    private final AtomicLong downlinkCount = new AtomicLong();

    public WsClient(String clientId, Channel channel, Set<String> modules, String ip, String uri) {
        this.clientId = clientId;
        this.channel = channel;
        this.modules = modules;
        this.ip = ip;
        this.uri = uri;
        this.createTime = System.currentTimeMillis();
        this.lastPongTime = this.createTime;
        this.lastActiveTime = this.createTime;
    }
}
