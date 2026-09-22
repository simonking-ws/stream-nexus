package com.simonking.nexus.websocket.core;

import com.simonking.nexus.websocket.constant.WsConstants;
import com.simonking.nexus.websocket.enums.WsEvent;
import com.simonking.nexus.websocket.model.WsClient;
import com.simonking.nexus.websocket.model.WsMessage;
import com.simonking.nexus.websocket.registry.WsClientRegistry;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import com.simonking.stream.nexus.common.util.IdGenerator;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推送门面：寻址 + 扇出
 *
 * <p>寻址规则（与 SSE 模块保持一致，避免两套服务心智割裂）：
 * <pre>
 *   命中集合 = 模块订阅者 ∪ 全局(*)订阅者 ∪ 定向客户端ID，按客户端ID 去重
 * </pre>
 * 定向推送也会叠加全局订阅者：订阅 {@code *} 的客户端语义上「什么都收」。
 *
 * <p>「定向」的维度是<b>客户端ID</b>：终端建连时自带（推荐自己生成 UUID 并持久化），
 * 不带则由服务端生成后随建连回执下发，终端再上报给业务系统。
 * 它不像通道ID那样随重连变化，业务系统可以据此维护「用户 -> clientId」的映射。
 *
 * <p>关于成功数：Netty 的 write 是异步的，这里只能保证「已提交」。
 * 已失效的连接在提交时 future 立刻失败（同步失败），会计入 failed 并回收；
 * 提交后才发现失败的走监听器回收，不计入本次返回值——真正的兜底是心跳超时回收。
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsPusher {

    private final WsClientRegistry registry;

    private final JsonMapper jsonMapper;

    /**
     * 推送消息
     */
    public PushResult push(PushRequest request) {
        Collection<WsClient> targets = resolveTargets(request);
        String messageId = IdGenerator.nextId();
        if (targets.isEmpty()) {
            return PushResult.builder().messageId(messageId).total(0).success(0).failed(0).build();
        }

        WsMessage<Object> message = WsMessage.builder()
                .id(messageId)
                .event(WsEvent.MESSAGE)
                .bizModule(request.getBizModule())
                .action(request.getAction())
                .ts(System.currentTimeMillis())
                .data(request.getData())
                .build();

        String payload;
        try {
            payload = jsonMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("[ws] 消息序列化失败, messageId={}", messageId, e);
            return PushResult.builder().messageId(messageId).total(targets.size()).success(0)
                    .failed(targets.size()).build();
        }

        int success = 0;
        int failed = 0;
        for (WsClient client : targets) {
            try {
                client.getChannel().writeAndFlush(new TextWebSocketFrame(payload))
                        .addListener(future -> {
                            if (!future.isSuccess()) {
                                log.warn("[ws] 下发失败, clientId={}, messageId={}", client.getClientId(), messageId);
                                registry.remove(client.getClientId());
                            }
                        });
                client.getDownlinkCount().incrementAndGet();
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("[ws] 写入异常, clientId={}, messageId={}", client.getClientId(), messageId, e);
                registry.remove(client.getClientId());
            }
        }
        return PushResult.builder().messageId(messageId).total(targets.size())
                .success(success).failed(failed).build();
    }

    /**
     * 按模块 + 定向列表解析目标连接
     *
     * <p>{@code request.getClientIds()} 是 {@code nexus-common} 的通用字段名，
     * 在本服务里承载的就是「客户端ID」——与 SSE 共用同一个契约，字段名不做改动。
     */
    private Collection<WsClient> resolveTargets(PushRequest request) {
        boolean byModule = StringUtils.hasText(request.getBizModule());
        boolean byClient = !CollectionUtils.isEmpty(request.getClientIds());
        if (!byModule && !byClient) {
            return java.util.List.of();
        }
        Map<String, WsClient> result = new LinkedHashMap<>();
        if (byModule) {
            if (WsConstants.isGlobal(request.getBizModule())) {
                for (WsClient client : registry.all()) {
                    result.putIfAbsent(client.getClientId(), client);
                }
            } else {
                for (WsClient client : registry.byModule(request.getBizModule())) {
                    result.putIfAbsent(client.getClientId(), client);
                }
            }
            // 全局订阅者无条件叠加
            for (WsClient client : registry.byModule(WsConstants.GLOBAL_MODULE)) {
                result.putIfAbsent(client.getClientId(), client);
            }
        }
        if (byClient) {
            for (WsClient client : registry.byClientIds(request.getClientIds())) {
                result.putIfAbsent(client.getClientId(), client);
            }
        }
        return result.values();
    }
}
