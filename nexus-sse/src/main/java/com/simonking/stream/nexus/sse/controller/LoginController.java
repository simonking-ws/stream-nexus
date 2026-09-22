package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.sse.auth.AdminAuthFilter;
import com.simonking.stream.nexus.sse.config.AdminAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理页登录 / 登出
 *
 * <p>登录态就是 session 里一个标记（{@link AdminAuthFilter#SESSION_KEY}）：
 * 校验在 {@link AdminAuthFilter}，写标记在这里，登出把 session 作废。
 * 不上 Spring Security：这里只需要「一个共享账号守住运维页」，引入一整套
 * 用户体系反而要多维护一堆与本服务无关的概念。
 *
 * @author simonking
 */
@Controller
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final AdminAuthProperties properties;

    public LoginController(AdminAuthProperties properties) {
        this.properties = properties;
    }

    /**
     * 登录页：已登录时直接回跳，省得在登录页再输一次
     */
    @GetMapping("/login")
    public String login(@RequestParam(value = "redirect", required = false) String redirect,
                        @RequestParam(value = "error", required = false) String error,
                        HttpServletRequest request,
                        Model model) {
        String target = safeRedirect(redirect);
        if (isLoggedIn(request)) {
            return "redirect:" + target;
        }
        model.addAttribute("title", "登录 · Stream Nexus");
        model.addAttribute("error", error != null);
        model.addAttribute("redirect", target);
        return "login";
    }

    /**
     * 提交登录：失败回登录页（带 {@code error}），成功写 session 后回跳
     *
     * <p>登录成功换一次 sessionId（{@code changeSessionId}）：挡住会话固定攻击——
     * 攻击者在受害者登录前塞一个已知 sessionId，登录后就自动拥有了登录态。
     */
    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          @RequestParam(value = "redirect", required = false) String redirect,
                          HttpServletRequest request) {
        String target = safeRedirect(redirect);
        if (!matches(username, password)) {
            log.warn("管理页登录失败：username={}, ip={}", username, request.getRemoteAddr());
            return "redirect:/login?error=1&redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(AdminAuthFilter.SESSION_KEY, username);
        request.changeSessionId();
        return "redirect:" + target;
    }

    /**
     * 登出：整个 session 作废（不只是删标记），回到登录页
     */
    @GetMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    private boolean isLoggedIn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(AdminAuthFilter.SESSION_KEY) != null;
    }

    /**
     * 账号 + 口令都要对上才算通过；两边都走常量时间比较，避免时序侧信道
     */
    private boolean matches(String username, String password) {
        return constantTimeEquals(properties.getUsername(), username)
                && constantTimeEquals(properties.getPassword(), password);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 只接受站内绝对路径：挡掉 {@code //evil.com}、{@code http://evil.com} 这类开放重定向
     */
    private String safeRedirect(String redirect) {
        if (!StringUtils.hasText(redirect)) {
            return "/admin";
        }
        String value = redirect.trim();
        return value.startsWith("/") && !value.startsWith("//") ? value : "/admin";
    }
}
