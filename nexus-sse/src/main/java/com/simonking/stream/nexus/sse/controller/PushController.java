package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import com.simonking.stream.nexus.sse.auth.PushAppRegistry;
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

    private final PushAppRegistry appRegistry;

    /**
     * 推送：按业务模块广播、按客户端定向，或两者同时指定
     *
     * <p>{@code bizModule} 缺省（null / 空白）按全局模块 {@link SseConstants#GLOBAL_MODULE} 处理：
     * 广播给全部在线连接，与显式传 {@code *} 完全等价——省掉「只想群发还得知道模块名」这一道门槛，
     * 也让「什么寻址字段都不填」不再是 400。
     *
     * <p>归一化必须放在这里、且在白名单校验**之前**：否则受限应用（白名单只有某几个模块）
     * 只要把模块留空就能广播全员，等于绕过 {@link #checkModulePermission}。
     */
    @PostMapping("/push")
    public PushResult push(@RequestBody PushRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request must not be empty");
        }

        normalizeBizModule(request);

        List<String> allowed = (List<String>) httpRequest.getAttribute(SseConstants.ATTR_ALLOWED_MODULES);
        checkModulePermission(allowed, request.getBizModule());
        return pusher.push(request);
    }

    /**
     * 缺省业务模块 → 全局模块 {@code *}
     *
     * <p>{@code clientIds} 也为空时归一化为 {@code *}（全模块广播）；
     * 但同时带了 {@code clientIds} 时保持纯定向——那是一次一对一推送，
     * 漏填模块不该把消息扩散给所有连接（否则「定向推送不扩散」这条约定就被悄悄破了）。
     */
    private void normalizeBizModule(PushRequest request) {
        if (StringUtils.hasText(request.getBizModule())) {
            return;
        }
        request.setBizModule(CollectionUtils.isEmpty(request.getClientIds())
                ? SseConstants.GLOBAL_MODULE
                : null);
    }

    /**
     * 业务模块白名单校验：限制该密钥可推送的模块范围
     */
    private void checkModulePermission(List<String> allowed, String bizModule) {
        if (!appRegistry.isAuthEnabled() || allowed == null || !StringUtils.hasText(bizModule)) {
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
