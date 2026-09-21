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

    /**
     * 是否开启**建连**鉴权（作用于 {@code /sse/subscribe}，默认关闭）
     *
     * <p>与 {@link #authEnabled} 是两件事：后者管「谁能推」，这里管「谁能连」。
     * 默认 false 是为了订阅侧零改动可跑通——浏览器 {@code EventSource} 带不了自定义请求头，
     * 开启鉴权后客户端必须在查询参数 {@code token} 上带令牌（也支持 {@code X-Sse-Token} 头），
     * 属于接入方需要配合的破坏性改动，故默认放开。
     */
    private boolean connectAuthEnabled = false;

    /**
     * 建连令牌：所有订阅方共用的共享口令，与 {@link #connectAuthEnabled} 配对配置
     *
     * <p>开关打开而此处留空时，建连请求**全部拒绝**（失败关闭）——
     * 宁可一个都连不上，也不能因为漏配而变相放行。
     */
    private String connectAuthToken = "";
}
