package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.common.enums.SseEvent;
import com.simonking.stream.nexus.common.model.SseMessage;
import com.simonking.stream.nexus.common.util.IdGenerator;
import com.simonking.stream.nexus.common.util.IpUtils;
import com.simonking.stream.nexus.common.util.NexusUtils;
import com.simonking.stream.nexus.sse.config.SseProperties;
import com.simonking.stream.nexus.sse.connection.SseClient;
import com.simonking.stream.nexus.sse.connection.SseClientRegistry;
import com.simonking.stream.nexus.sse.core.SseSender;
import jakarta.servlet.http.HttpServletRequest;
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
     * <p>客户端ID 由<b>服务端</b>生成（{@link NexusUtils#newClientId()}），客户端只带订阅模块即可：
     * 自带ID 意味着客户端可以声明任意身份，服务端要么承担被冒用的风险，要么再叠一层令牌校验。
     * 生成的 ID 随建连回执下发，客户端保存后用于心跳应答与定向推送寻址。
     *
     * @param modules 订阅的业务模块，逗号分隔，如 {@code lot,order}。
     *                按模块推送的路由依据，取值必须与推送侧完全一致（含大小写）。
     *                缺省或为空白时默认订阅 {@link SseConstants#GLOBAL_MODULE}（{@code *}，接收全部按模块推送的消息）
     */
    @GetMapping(path = "/sse/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam(defaultValue = NexusConstants.GLOBAL_MODULE) String modules,
                                HttpServletRequest request) {
        if (properties.getMaxConnections() > 0 && registry.size() >= properties.getMaxConnections()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "connection limit reached");
        }

        // 全局模块（* / Global 等写法）归一化成常量字面量，保证与倒排索引（大小写敏感）对齐
        Set<String> moduleSet = Arrays.stream(modules.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(m -> NexusUtils.isGlobal(m) ? NexusConstants.GLOBAL_MODULE : m)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (moduleSet.isEmpty()) {
            moduleSet.add(NexusConstants.GLOBAL_MODULE);
        }

        // 客户端ID 服务端生成：客户端不传、也无从伪造
        String clientId = NexusUtils.newClientId();

        // 0 = 永不过期，回收完全交给 HeartbeatTask
        SseEmitter emitter = new SseEmitter(0L);
        SseClient client = new SseClient(clientId, emitter, moduleSet, resolveClientIp(request));

        // UUID 碰撞概率可忽略，正常路径走不到；保留兜底，避免真的撞了以后两条连接互相覆盖
        if (!registry.add(client)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clientId already connected: " + clientId);
        }

        emitter.onCompletion(() -> registry.remove(clientId));
        emitter.onError(e -> registry.remove(clientId));

        // 首条消息：告知客户端心跳节奏（客户端收到后应重新拉取全量业务状态）
        try {
            sender.send(client, SseMessage.builder()
                    .id(IdGenerator.nextId())
                    .event(SseEvent.MESSAGE)
                    .bizModule(SseConstants.SYS_MODULE)
                    .action(NexusConstants.ACTION_CONNECTED)
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
     * 心跳应答：客户端收到 {@link SseEvent#PING} 后上报，证明「我还活着」。
     *
     * <p>永不过期策略下这是判断连接存活的**唯一依据**——半开连接下服务端 {@code send()} 依然返回成功，
     * 必须由客户端应答才能发现死连接。无需节流，收到即回。
     *
     * <p>只依赖 {@code clientId}（建连回执里服务端下发的那个，重连会变）；
     * {@code body} 可选（预留给 RTT / 序号等诊断信息），当前不参与判定。
     */
    @PostMapping("/sse/pong")
    public Map<String, Object> pong(@RequestParam(SseConstants.PARAM_CLIENT_ID) String clientId,
                                    @RequestBody(required = false) SseMessage<Object> message) {
        SseClient client = registry.get(clientId);
        boolean ok = false;
        if (client != null) {
            client.setLastPongTime(System.currentTimeMillis());
            ok = true;
        }
        return Map.of("ok", ok);
    }

    /**
     * 解析客户端 IP：优先信任代理头（Nginx 反代下 remoteAddr 只会是网关地址），
     * 取不到再退回 TCP 对端地址。仅供台账展示，不参与任何鉴权判定。
     *
     * <p>具体解析与归一化逻辑在 {@link IpUtils}，与 nexus-websocket 共用同一套口径：
     * 同一个网关后面的两个服务，台账里展示的 IP 必须一致，否则排障时会对不上。
     */
    private String resolveClientIp(HttpServletRequest request) {
        return IpUtils.resolve(request.getHeader(NexusConstants.HEADER_X_FORWARDED_FOR),
                request.getHeader(NexusConstants.HEADER_X_REAL_IP),
                request.getRemoteAddr());
    }

    /**
     * 建连回执：{@code clientId} 是客户端心跳应答与业务系统定向推送的唯一依据，
     * 必须取服务端生成的那个值（重连即换，故客户端每次建连后都要覆盖保存）
     */
    private Map<String, Object> buildWelcome(String clientId, Set<String> modules) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(SseConstants.PARAM_CLIENT_ID, clientId);
        data.put("modules", modules);
        data.put("heartbeatInterval", properties.getHeartbeatInterval().toMillis());
        return data;
    }
}
