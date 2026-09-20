package com.simonking.stream.nexus.sse.auth;

import com.simonking.stream.nexus.common.constant.SseConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 推送鉴权拦截器
 *
 * <p>校验请求头 {@code X-Sse-AppId}（应用标识）与 {@code X-Sse-Key}（应用密钥）：
 * 前者定位应用、后者证明身份，二者必须配对匹配；通过后把该应用允许推送的业务模块写入请求属性，
 * 供 {@link com.simonking.stream.nexus.sse.controller.PushController} 做白名单校验。
 *
 * <p>需要更强安全（防重放、防篡改）时，可在此基础上升级为 HMAC 签名，
 * 接口位置不变。
 *
 * @author simonking
 */
@Component
@RequiredArgsConstructor
public class PushAuthInterceptor implements HandlerInterceptor {

    private final PushAppRegistry appRegistry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 预检 OPTIONS 不带鉴权头，放行交给跨域处理短路返回；否则浏览器侧跨域推送会被 401 挡掉
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        if (!appRegistry.isAuthEnabled()) {
            return true;
        }

        String appId = request.getHeader(SseConstants.HEADER_APP_ID);
        String apiKey = request.getHeader(SseConstants.HEADER_API_KEY);
        // 先用 appId 定位应用（决定白名单归属），再常量时间比对密钥——顺序不可颠倒，
        // 否则「先按密钥找、再比 appId」会让密钥探测与身份探测混在一起
        PushApp client = appRegistry.find(appId).orElse(null);

        if (client == null || !constantTimeEquals(client.apiKey(), apiKey)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\"}");
            return false;
        }

        request.setAttribute(SseConstants.ATTR_ALLOWED_MODULES, client.allowedModules());
        return true;
    }

    /**
     * 常量时间比较，避免时序侧信道
     */
    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
