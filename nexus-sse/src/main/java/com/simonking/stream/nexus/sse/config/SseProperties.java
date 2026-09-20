package com.simonking.stream.nexus.sse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SSE 推送配置
 *
 * <p>采用「永不过期」策略：连接不因空闲被回收，回收完全由回执与软重置驱动。
 *
 * @author simonking
 */
@Data
@ConfigurationProperties(prefix = "nexus.sse")
public class SseProperties {

    /**
     * 心跳间隔（服务端 → 客户端）：下发 PING，客户端收到后立即回 PONG。
     * 同时用于压制 LB / NAT 的空闲断链
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /**
     * 心跳超时：超过该时间未收到 PONG 则判定客户端失联并回收连接。
     *
     * <p>应 ≥ 3 倍 {@link #heartbeatInterval}，以容忍连续若干次 PONG 丢失（网络抖动、页面短暂冻结）
     */
    private Duration heartbeatTimeout = Duration.ofSeconds(90);

    /**
     * 最大存活时间：达到后主动回收，客户端自动重连（软重置）。0 表示不限制
     */
    private Duration maxLifetime = Duration.ofMinutes(0);

    /**
     * 最大连接数，0 表示不限制
     */
    private int maxConnections = 30000;

    /**
     * 是否开启推送鉴权
     *
     * <p>注意：推送应用（appId / apiKey）不在此配置，而是运行时维护在
     * {@code PushAppRegistry}：内置默认应用 {@code test / test_secret}，
     * 其余应用在 {@code /admin} 管理页「推送应用」页签增删（仅内存生效）
     */
    private boolean authEnabled = true;
}
