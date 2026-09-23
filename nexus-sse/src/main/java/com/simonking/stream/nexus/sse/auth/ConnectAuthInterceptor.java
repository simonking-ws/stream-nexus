package com.simonking.stream.nexus.sse.auth;

import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.sse.config.SseProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 建连鉴权拦截器（作用于 {@code /sse/subscribe}）
 *
 * <p>与 {@link PushAuthInterceptor} 职责不同：那个管「谁能推」，这个管「谁能连」。
 * 校验一个**共享令牌**（{@code nexus.sse.connect-auth-token}），所有订阅方共用同一个口令，
 * 不做应用级身份区分——订阅侧只有「能不能连」这一个问题，接入方身份由业务侧自己管。
 *
 * <p>令牌取值的顺序：先 {@code X-Sse-Token} 请求头，再 {@code token} 查询参数。
 * 保留查询参数通道是必须的：浏览器 {@code EventSource} 建连时无法设置自定义请求头。
 *
 * <p>默认关闭（{@code nexus.sse.connect-auth-enabled=false}）：开启会让所有客户端改接入方式，
 * 属于破坏性改动，交由部署方按需打开。
 *
 * @author simonking
 */
@Component
public class ConnectAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ConnectAuthInterceptor.class);

    private final SseProperties properties;

    public ConnectAuthInterceptor(SseProperties properties) {
        this.properties = properties;
        if (properties.isConnectAuthEnabled() && !StringUtils.hasText(properties.getConnectAuthToken())) {
            // 提前把漏配喊出来，避免上线后「明明开了鉴权却谁都连不上」只能靠抓包定位
            log.warn("建连鉴权已开启但未配置 nexus.sse.connect-auth-token，建连请求将全部拒绝");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isConnectAuthEnabled()) {
            return true;
        }

        String expected = properties.getConnectAuthToken();
        // 失败关闭：开关开了却没配令牌，一律拒绝，不静默放行
        if (!StringUtils.hasText(expected)) {
            writeUnauthorized(response);
            return false;
        }

        String actual = resolveToken(request);
        if (actual == null || !constantTimeEquals(expected, actual)) {
            writeUnauthorized(response);
            return false;
        }
        return true;
    }

    /**
     * 头优先、参数兜底：{@code EventSource} 只能走参数，非浏览器客户端可用头
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(SseConstants.HEADER_CONNECT_TOKEN);
        if (StringUtils.hasText(header)) {
            return header.trim();
        }
        String param = request.getParameter(NexusConstants.PARAM_CONNECT_TOKEN);
        return StringUtils.hasText(param) ? param.trim() : null;
    }

    /**
     * 常量时间比较，避免时序侧信道
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":401,\"message\":\"unauthorized: invalid connect token\"}");
    }
}
