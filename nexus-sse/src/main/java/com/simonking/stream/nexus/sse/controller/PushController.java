package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import com.simonking.stream.nexus.sse.config.SseProperties;
import com.simonking.stream.nexus.sse.core.SsePusher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 外部推送入口（需鉴权，见 {@link com.simonking.stream.nexus.sse.auth.PushAuthInterceptor}）
 *
 * @author simonking
 */
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class PushController {

    private final SsePusher pusher;

    private final SseProperties properties;

    /**
     * 推送：按业务模块广播、按客户端定向，或两者同时指定
     */
    @PostMapping("/push")
    public PushResult push(@RequestBody PushRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request must not be empty");
        }

        if (!StringUtils.hasText(request.getBizModule()) && CollectionUtils.isEmpty(request.getClientIds())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bizModule or clientIds is required");
        }

        List<String> allowed = (List<String>) httpRequest.getAttribute(SseConstants.ATTR_ALLOWED_MODULES);
        checkModulePermission(allowed, request.getBizModule());
        return pusher.push(request);
    }

    /**
     * 业务模块白名单校验：限制该密钥可推送的模块范围
     */
    private void checkModulePermission(List<String> allowed, String bizModule) {
        if (!properties.isAuthEnabled() || allowed == null || !StringUtils.hasText(bizModule)) {
            return;
        }
        if (allowed.contains(SseConstants.MODULE_WILDCARD)) {
            return;
        }
        if (allowed.stream().noneMatch(m -> m.equals(bizModule))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bizModule not allowed: " + bizModule);
        }
    }
}
