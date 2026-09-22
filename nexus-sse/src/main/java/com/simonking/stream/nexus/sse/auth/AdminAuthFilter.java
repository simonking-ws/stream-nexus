package com.simonking.stream.nexus.sse.auth;

import com.simonking.stream.nexus.sse.config.AdminAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 管理页登录拦截（session 态）
 *
 * <p>只守「运维入口」这一块：管理页 {@code /admin}、推送测试页 {@code /console}，
 * 以及它们背后那组运维接口 {@code /sse/admin/**}（连接台账、强制下线、应用台账）。
 * 其余路径一律不碰——{@code /sse/subscribe} 是订阅端入口、{@code /sse/push} 走
 * {@link PushAuthInterceptor} 的 appId / apiKey 鉴权，两者各有各的验证方式，
 * 不能因为「顺手」把浏览器登录态加进去，否则会把接入方的调用链一起打断。
 *
 * <p>用 Filter 而非 {@code HandlerInterceptor}：运维接口是 {@code @RestController}，
 * 页面是 {@code @Controller}，两者路径前缀不同，Filter 一次覆盖更省心，
 * 也避免将来加静态资源 / 错误页时出现「以为拦住了其实没拦住」的缝隙。
 *
 * <p>未登录的处置分两类：页面请求 302 到 {@code /login}（带上回跳地址），
 * 接口请求直接 401 JSON——接口不该收到一段登录页 HTML，那会让页面脚本把它当数据处理。
 *
 * @author simonking
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    /**
     * 登录态在 session 里的键：值是登录账号，只用于展示与判空
     */
    public static final String SESSION_KEY = "SSE_ADMIN_USER";

    /**
     * 需要登录才能访问的路径（Ant 风格）
     *
     * <p>刻意写成白名单而非「拦截 /** 再放行登录页」：漏配时是「少拦一个」而不是「全站打不开」，
     * 后者一旦发生，连登录页都进不去，属于灾难级误伤。
     */
    private static final String[] PROTECTED_PATTERNS = {"/admin", "/console", "/sse/admin/**"};

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final AdminAuthProperties properties;

    public AdminAuthFilter(AdminAuthProperties properties) {
        this.properties = properties;
        if (properties.isAuthEnabled()
                && (!StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getPassword()))) {
            // 失败关闭：账号或口令漏配时谁都登不进来，与其静默放行不如先把问题喊出来
            log.warn("管理页登录已开启但未完整配置账号/口令（nexus.sse.admin.username / password），登录将全部失败");
        }
    }

    /**
     * 开关关闭、或不在受保护路径上，直接放行
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isAuthEnabled()) {
            return true;
        }
        String path = requestPath(request);
        for (String pattern : PROTECTED_PATTERNS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 去掉 context-path 后的路径：{@code PROTECTED_PATTERNS} 与回跳地址都以它为基准，
     * 否则服务挂在非根 context-path 下时，受保护路径会全部匹配不上（等于没拦）
     */
    private String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_KEY) != null) {
            chain.doFilter(request, response);
            return;
        }

        String path = requestPath(request);
        // 接口：401 JSON；页面：302 到登录页并带上回跳地址
        if (path.startsWith("/sse/")) {
            writeUnauthorized(response);
        } else {
            String back = path + (StringUtils.hasText(request.getQueryString()) ? "?" + request.getQueryString() : "");
            response.sendRedirect(request.getContextPath() + "/login?redirect="
                    + URLEncoder.encode(back, StandardCharsets.UTF_8));
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":401,\"message\":\"unauthorized: please login first\"}");
    }
}
