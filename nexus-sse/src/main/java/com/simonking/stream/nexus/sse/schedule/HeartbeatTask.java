package com.simonking.stream.nexus.sse.schedule;

import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.common.enums.SseEvent;
import com.simonking.stream.nexus.common.model.SseMessage;
import com.simonking.stream.nexus.sse.config.SseProperties;
import com.simonking.stream.nexus.sse.connection.SseClient;
import com.simonking.stream.nexus.sse.connection.SseClientRegistry;
import com.simonking.stream.nexus.sse.core.SseSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳（PING/PONG）+ 连接回收
 *
 * <p>永不过期策略下，容器不会主动超时，因此这里是连接回收的主战场：
 * <ol>
 *     <li>每 {@code heartbeatInterval} 向每条连接下发一条 {@link SseEvent#PING}，
 *         客户端收到后立即回 {@link SseEvent#PONG}；</li>
 *     <li>{@code now - lastPongTime > heartbeatTimeout} 判定失联并回收。
 *         这是发现「半开连接」的唯一手段——半开连接下 {@code send()} 依然返回成功，
 *         只有客户端应答才能证明它还活着；</li>
 *     <li>软重置：{@code now - createTime > maxLifetime}，保证连接「有出有进」。</li>
 * </ol>
 *
 * <p>心跳采用「问-答」而非「客户端定时上报」：服务端明确知道自己在什么时候期待应答，
 * 判定阈值与实际发送节奏天然对齐，也不会出现「页面没有业务消息就永远不上报」的空洞期。
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatTask {

    private final SseClientRegistry registry;

    private final SseSender sender;

    private final SseProperties properties;

    @Scheduled(fixedDelayString = "${nexus.sse.heartbeat-interval:15000}")
    public void tick() {
        long now = System.currentTimeMillis();
        long heartbeatTimeout = properties.getHeartbeatTimeout().toMillis();
        long maxLifetime = properties.getMaxLifetime().toMillis();

        for (SseClient client : registry.all()) {
            // 1. 长时间未回心跳：客户端失联（半开连接 / 页面被冻结 / 进程被杀）
            if (heartbeatTimeout > 0 && now - client.getLastPongTime() > heartbeatTimeout) {
                log.info("[sse] recycle by heartbeat timeout, clientId={}, lastPong={}",
                        client.getClientId(), client.getLastPongTime());
                registry.remove(client.getClientId());
                continue;
            }
            // 2. 软重置：达到最大存活时间，客户端会自动重连
            if (maxLifetime > 0 && now - client.getCreateTime() > maxLifetime) {
                log.info("[sse] recycle by max lifetime, clientId={}", client.getClientId());
                registry.remove(client.getClientId());
                continue;
            }
            // 3. 下发心跳：客户端收到后应回 PONG（不设置 id，避免污染客户端的 Last-Event-ID）
            try {
                sender.send(client, SseMessage.builder()
                        .event(SseEvent.PING)
                        .bizModule(SseConstants.SYS_MODULE)
                        .action(SseConstants.ACTION_PING)
                        .ts(now)
                        .build());
            } catch (Exception e) {
                log.info("[sse] recycle by heartbeat failure, clientId={}", client.getClientId());
                registry.remove(client.getClientId());
            }
        }
    }
}
