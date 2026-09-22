package com.simonking.nexus.websocket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * WebSocket 服务配置
 *
 * <p>两个端口职责分明：{@code ws-port}（Netty）面向海量终端，只负责收推送；
 * HTTP 端口（{@code server.port}，Spring MVC）承载 REST 推送接口与管理界面，
 * 面向少量可信业务系统。安全策略也因此不同——REST 端口应当只对内网开放。
 *
 * @author simonking
 */
@Data
@ConfigurationProperties(prefix = "nexus.ws")
public class WsProperties {

    /**
     * WebSocket 服务端口（终端建连）
     */
    private int wsPort = 9090;

    /**
     * WebSocket 握手路径
     */
    private String wsPath = "/ws";

    /**
     * WebSocket 心跳间隔：写空闲这么久就下发一次 PING
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /**
     * WebSocket 心跳超时：读空闲这么久就判定失联并关闭。应 >= 3 倍心跳间隔
     */
    private Duration heartbeatTimeout = Duration.ofSeconds(90);

    /**
     * 最大 WebSocket 连接数，0 表示不限制
     */
    private int maxConnections = 30000;

    /**
     * 单个 WebSocket 帧最大长度（字节）
     */
    private int maxFrameLength = 65536;

    /**
     * Netty boss 线程数
     */
    private int bossThreads = 1;

    /**
     * Netty worker 线程数，0 表示取 Netty 默认值（CPU 核数 * 2）
     */
    private int workerThreads = 0;

    /**
     * 推送鉴权开关（appId + apiKey）
     */
    private boolean authEnabled = true;

    /**
     * 建连鉴权开关（共享令牌）
     */
    private boolean connectAuthEnabled = false;

    /**
     * 建连令牌。开关打开但此处留空时，建连请求一律拒绝（失败关闭）
     */
    private String connectAuthToken = "";

    /**
     * 控制台页面拼接 WebSocket 地址时使用的对外地址（host 或 host:port）
     */
    private String publicEndpoint = "";
}
