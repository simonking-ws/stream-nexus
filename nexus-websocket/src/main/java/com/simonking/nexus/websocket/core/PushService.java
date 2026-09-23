package com.simonking.nexus.websocket.core;

import com.simonking.nexus.websocket.auth.PushAppRegistry;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 推送服务：REST 推送接口（{@code POST /ws/push}）的业务逻辑
 *
 * <p>业务系统不写任何 Netty / WebSocket 代码，一次 HTTP POST 就把消息交给本服务，
 * 由它查连接注册表并扇出到终端。
 *
 * @author simonking
 */
@Service
@RequiredArgsConstructor
public class PushService {

    private final WsPusher pusher;

    /**
     * 推送消息
     *
     * @param request        推送请求
     * @param allowedModules 应用可用模块白名单，null 表示鉴权关闭、不做越权校验
     */
    public PushResult push(PushRequest request, List<String> allowedModules) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request must not be empty");
        }
        if (!StringUtils.hasText(request.getBizModule()) && CollectionUtils.isEmpty(request.getClientIds())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bizModule or clientIds is required");
        }
        if (!PushAppRegistry.moduleAllowed(allowedModules, request.getBizModule())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bizModule not allowed: " + request.getBizModule());
        }
        return pusher.push(request);
    }
}
