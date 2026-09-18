package com.simonking.stream.nexus.sse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
     */
    private boolean authEnabled = true;

    /**
     * 鉴权客户端列表
     */
    private List<AuthClient> clients = new ArrayList<>();

    /**
     * 推送鉴权客户端
     */
    @Data
    public static class AuthClient {

        /**
         * 应用标识，对应请求头 {@code X-Sse-AppId}。用于定位调用方，白名单归属应用
         */
        private String appId;

        /**
         * 应用密钥，对应请求头 {@code X-Sse-Key}。与 appId 配对校验，必须同时匹配
         */
        private String apiKey;

        /**
         * 允许推送的业务模块，如 {@code lot} / {@code order}；{@code *} 表示不限制
         */
        private List<String> allowedModules = List.of("*");
    }
}
