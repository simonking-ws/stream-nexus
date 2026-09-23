package com.simonking.nexus.websocket.core;

import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * TCP 通道的推送服务：业务系统 --TCP--> 本服务 --> 终端
 *
 * <p>与 REST 通道的 {@link PushService} 是同一个 {@code WsPusher}，区别只有两点：
 * <ul>
 *     <li><b>不做应用鉴权</b>：TCP 端口只对内网开放，能连上即视为可信业务系统，
 *         而它推的消息仍要按终端的订阅模块扇出，拿不到任何「越权」能力；</li>
 *     <li><b>错误表达</b>：REST 抛 {@code ResponseStatusException} 换 HTTP 状态码，
 *         TCP 抛 {@code IllegalArgumentException}，由帧处理器翻译成一条 {@code ERROR} 报文回给对端
 *         ——长连接上没有状态码可用，只能把原因写进消息体。</li>
 * </ul>
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TcpPushService {

    private final WsPusher pusher;

    /**
     * 推送消息
     *
     * @param request 推送请求
     * @throws IllegalArgumentException 参数不合法，异常消息直接回给业务系统
     */
    public PushResult push(PushRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be empty");
        }
        if (!StringUtils.hasText(request.getBizModule()) && CollectionUtils.isEmpty(request.getClientIds())) {
            throw new IllegalArgumentException("bizModule or clientIds is required");
        }
        return pusher.push(request);
    }
}
