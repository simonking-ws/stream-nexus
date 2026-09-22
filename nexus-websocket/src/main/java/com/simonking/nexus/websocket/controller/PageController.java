package com.simonking.nexus.websocket.controller;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.constant.WsConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面跳转
 *
 * <p>服务端的运行时参数（端口、心跳节奏、鉴权开关）一律通过 Model 下发，
 * 页面里不写死任何常量，改配置即生效。
 *
 * @author simonking
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final WsProperties properties;

    @GetMapping("/")
    public String home() {
        return "redirect:/admin";
    }

    /**
     * 连接管理页（默认页）
     */
    @GetMapping("/admin")
    public String admin(Model model) {
        fill(model, "admin", "WebSocket 连接管理");
        return "admin";
    }

    /**
     * 推送测试页
     */
    @GetMapping("/console")
    public String console(Model model) {
        fill(model, "console", "WebSocket 推送测试");
        return "console";
    }

    private void fill(Model model, String active, String title) {
        model.addAttribute("active", active);
        model.addAttribute("title", title);
        model.addAttribute("wsPort", properties.getWsPort());
        model.addAttribute("wsPath", properties.getWsPath());
        model.addAttribute("pushPath", WsConstants.PUSH_PATH);
        model.addAttribute("publicEndpoint", properties.getPublicEndpoint());
        model.addAttribute("heartbeatInterval", properties.getHeartbeatInterval().toSeconds());
        model.addAttribute("heartbeatTimeout", properties.getHeartbeatTimeout().toSeconds());
        model.addAttribute("maxConnections", properties.getMaxConnections());
        model.addAttribute("connectAuthEnabled", properties.isConnectAuthEnabled());
        model.addAttribute("authEnabled", properties.isAuthEnabled());
    }
}
