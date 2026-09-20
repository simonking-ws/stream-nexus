package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.common.enums.EventEnum;
import com.simonking.stream.nexus.common.model.SseMessage;
import com.simonking.stream.nexus.common.util.IdGenerator;
import com.simonking.stream.nexus.sse.config.SseProperties;
import com.simonking.stream.nexus.sse.connection.SseClient;
import com.simonking.stream.nexus.sse.connection.SseClientRegistry;
import com.simonking.stream.nexus.sse.core.SseSender;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SSE 连接端点
 *
 * @author simonking
 */
@RestController
@RequiredArgsConstructor
public class SseController {

    private final SseClientRegistry registry;

    private final SseSender sender;

    private final SseProperties properties;

    /**
     * 建立 SSE 长连接（永不过期）
     *
     * @param clientId 客户端ID，由客户端生成（如 crypto.randomUUID()），需保证唯一
     * @param modules  订阅的业务模块，逗号分隔，如 {@code lot,order}。
     *                 按模块推送的路由依据，取值必须与推送侧完全一致（含大小写）。
     *                 缺省或为空白时默认订阅 {@link SseConstants#GLOBAL_MODULE}（接收全部按模块推送的消息）
     */
    @GetMapping(path = "/sse/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam String clientId,
                                @RequestParam(defaultValue = SseConstants.GLOBAL_MODULE) String modules) {
        if (properties.getMaxConnections() > 0 && registry.size() >= properties.getMaxConnections()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "connection limit reached");
        }

        // global 归一化成常量字面量，保证与倒排索引（大小写敏感）对齐
        Set<String> moduleSet = Arrays.stream(modules.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(m -> SseConstants.isGlobal(m) ? SseConstants.GLOBAL_MODULE : m)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (moduleSet.isEmpty()) {
            moduleSet.add(SseConstants.GLOBAL_MODULE);
        }

        // 0 = 永不过期，回收完全交给 HeartbeatTask
        SseEmitter emitter = new SseEmitter(0L);
        SseClient client = new SseClient(clientId, emitter, moduleSet);

        if (!registry.add(client)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clientId already connected: " + clientId);
        }

        emitter.onCompletion(() -> registry.remove(clientId));
        emitter.onError(e -> registry.remove(clientId));

        // 首条消息：告知客户端心跳节奏（客户端收到后应重新拉取全量业务状态）
        try {
            sender.send(client, SseMessage.builder()
                    .id(IdGenerator.nextId())
                    .event(EventEnum.MESSAGE)
                    .bizModule(SseConstants.SYS_MODULE)
                    .action(SseConstants.ACTION_CONNECTED)
                    .ts(System.currentTimeMillis())
                    .data(buildWelcome(clientId, moduleSet))
                    .build());
        } catch (Exception e) {
            registry.remove(clientId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "init sse failed");
        }
        return emitter;
    }

    /**
     * 心跳应答：客户端收到 {@link EventEnum#PING} 后上报，证明「我还活着」。
     *
     * <p>永不过期策略下这是判断连接存活的**唯一依据**——半开连接下服务端 {@code send()} 依然返回成功，
     * 必须由客户端应答才能发现死连接。无需节流，收到即回。
     *
     * <p>只依赖 {@code clientId}；{@code body} 可选（预留给 RTT / 序号等诊断信息），当前不参与判定。
     */
    @PostMapping("/sse/pong")
    public Map<String, Object> pong(@RequestParam String clientId,
                                    @RequestBody(required = false) SseMessage<Object> message) {
        SseClient client = registry.get(clientId);
        boolean ok = false;
        if (client != null) {
            client.setLastPongTime(System.currentTimeMillis());
            ok = true;
        }
        return Map.of("ok", ok);
    }

    private Map<String, Object> buildWelcome(String clientId, Set<String> modules) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clientId", clientId);
        data.put("modules", modules);
        data.put("heartbeatInterval", properties.getHeartbeatInterval().toMillis());
        return data;
    }
}
