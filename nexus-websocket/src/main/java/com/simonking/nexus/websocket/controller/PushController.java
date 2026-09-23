package com.simonking.nexus.websocket.controller;

import com.simonking.nexus.websocket.auth.PushApp;
import com.simonking.nexus.websocket.auth.PushAppRegistry;
import com.simonking.stream.nexus.common.constant.WsConstants;
import com.simonking.nexus.websocket.core.PushService;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST 推送入口：业务系统推消息的<b>唯一</b>通道
 *
 * <p>业务系统不写任何 Netty / WebSocket 代码，一次 HTTP POST 即可：
 * <pre>
 * POST /ws/push
 * X-Ws-AppId: test
 * X-Ws-Key:   test_secret
 * {"bizModule":"order","action":"CREATE","data":{...}}
 * </pre>
 * 响应直接带回 {@code total / success / failed}，调用方无需等待任何异步确认。
 *
 * <p>鉴权用 {@code PushAppRegistry}：appId + apiKey 配对校验，并按应用限制可推的模块白名单。
 * 鉴权关闭（{@code nexus.ws.auth-enabled=false}）时白名单为 null，不做越权校验。
 *
 * @author simonking
 */
@RestController
@RequestMapping("/ws")
@RequiredArgsConstructor
public class PushController {

    private final PushService pushService;

    private final PushAppRegistry appRegistry;

    @PostMapping("/push")
    public PushResult push(@RequestBody(required = false) PushRequest request,
                           @RequestHeader(value = WsConstants.HEADER_APP_ID, required = false) String appId,
                           @RequestHeader(value = WsConstants.HEADER_API_KEY, required = false) String apiKey) {
        List<String> allowedModules = null;
        if (appRegistry.isAuthEnabled()) {
            PushApp app = appRegistry.authenticate(appId, apiKey);
            if (app == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized: invalid appId or apiKey");
            }
            allowedModules = app.allowedModules();
        }
        return pushService.push(request, allowedModules);
    }
}
