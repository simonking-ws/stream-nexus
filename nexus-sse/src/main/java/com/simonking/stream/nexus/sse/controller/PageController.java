package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.sse.config.SseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面跳转（Thymeleaf）
 *
 * <p>只负责「渲染哪个页面、往页面里塞什么服务端参数」，业务数据一律由页面自己调
 * {@code /sse/**} 接口拿——页面不该分担推送服务的状态查询职责。
 *
 * <p>默认页是管理页：运维是本服务最常打开的一屏，推送测试页退居 {@code /console}。
 *
 * <p>注意路径别与 {@code /sse/**} 冲突：这里是 {@code /admin}、{@code /console}，
 * 运维接口在 {@link AdminController} 的 {@code /sse/admin/**}。
 *
 * @author simonking
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final SseProperties properties;

    /**
     * 根路径直达管理页
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        fill(model, "admin", "SSE 连接管理");
        return "admin";
    }

    @GetMapping("/console")
    public String console(Model model) {
        fill(model, "console", "SSE 推送测试");
        return "console";
    }

    /**
     * 服务端参数：心跳节奏与连接上限由配置决定，页面不写死，避免配置改了页面还显示旧值
     */
    private void fill(Model model, String active, String title) {
        model.addAttribute("active", active);
        model.addAttribute("title", title);
        model.addAttribute("heartbeatInterval", properties.getHeartbeatInterval().toSeconds());
        model.addAttribute("heartbeatTimeout", properties.getHeartbeatTimeout().toSeconds());
        model.addAttribute("maxConnections", properties.getMaxConnections());
        // 下发给页面：开启建连鉴权时，推送测试页必须在连接前拦住没填令牌的情况，
        // 否则 EventSource 只会拿到一个 401 然后无脑重连，页面上看不出原因
        model.addAttribute("connectAuthEnabled", properties.isConnectAuthEnabled());
    }
}
