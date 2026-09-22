package com.simonking.nexus.websocket.controller;

import com.simonking.nexus.websocket.auth.PushApp;
import com.simonking.nexus.websocket.auth.PushAppRegistry;
import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.constant.WsConstants;
import com.simonking.nexus.websocket.model.WsClient;
import com.simonking.nexus.websocket.registry.WsClientRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维接口：连接台账 + 应用管理
 *
 * <p>与 SSE 模块保持同一套交互契约（{@code /ws/admin/**}），管理界面可无缝复用同一套心智。
 *
 * @author simonking
 */
@RestController
@RequestMapping("/ws/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final long START_TIME = ManagementFactory.getRuntimeMXBean().getStartTime();

    private final WsClientRegistry registry;

    private final PushAppRegistry appRegistry;

    private final WsProperties properties;

    /**
     * 全局概览：WebSocket 连接 + 服务运行信息
     */
    @GetMapping("/connections")
    public Map<String, Object> connections() {
        long now = System.currentTimeMillis();
        long heartbeatTimeout = properties.getHeartbeatTimeout().toMillis();

        List<Map<String, Object>> items = new ArrayList<>();
        for (WsClient client : registry.all()) {
            items.add(toView(client, now, heartbeatTimeout));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", registry.size());
        result.put("modules", registry.moduleStats());
        result.put("serverTime", now);
        result.put("startTime", START_TIME);
        result.put("uptime", Math.max(now - START_TIME, 0));
        result.put("heartbeatInterval", properties.getHeartbeatInterval().toMillis());
        result.put("heartbeatTimeout", heartbeatTimeout);
        result.put("maxConnections", properties.getMaxConnections());
        result.put("wsPort", properties.getWsPort());
        result.put("wsPath", properties.getWsPath());
        result.put("items", items);
        return result;
    }

    /**
     * 强制下线指定 WebSocket 连接
     */
    @DeleteMapping("/connections/{clientId}")
    public Map<String, Object> kick(@PathVariable String clientId) {
        boolean existed = registry.contains(clientId);
        registry.kick(clientId, WsConstants.ACTION_KICKED + " by admin");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", existed);
        result.put("clientId", clientId);
        return result;
    }

    /**
     * 推送应用列表
     */
    @GetMapping("/apps")
    public Map<String, Object> apps() {
        Map<String, Object> result = new LinkedHashMap<>(appRegistry.overview());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PushApp app : appRegistry.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("appId", app.appId());
            item.put("apiKey", app.apiKey());
            item.put("allowedModules", app.allowedModules());
            items.add(item);
        }
        result.put("items", items);
        return result;
    }

    /**
     * 生成随机密钥
     */
    @GetMapping("/apps/generate-key")
    public Map<String, Object> generateKey() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiKey", PushAppRegistry.generateApiKey());
        return result;
    }

    /**
     * 新增应用
     */
    @PostMapping("/apps")
    public Map<String, Object> addApp(@RequestBody AppRequest body) {
        if (body == null || !StringUtils.hasText(body.appId()) || !StringUtils.hasText(body.apiKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appId and apiKey are required");
        }
        if (appRegistry.contains(body.appId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appId already exists: " + body.appId());
        }
        appRegistry.save(body.appId().trim(), body.apiKey().trim(), body.allowedModules());
        return Map.of("ok", true, "appId", body.appId().trim());
    }

    /**
     * 修改应用：字段为 null / 空串表示不修改
     */
    @PutMapping("/apps/{appId}")
    public Map<String, Object> updateApp(@PathVariable String appId, @RequestBody AppUpdateRequest body) {
        PushApp existed = appRegistry.find(appId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "app not found: " + appId));
        String apiKey = (body == null || !StringUtils.hasText(body.apiKey())) ? existed.apiKey() : body.apiKey().trim();
        List<String> modules = (body == null || body.allowedModules() == null)
                ? existed.allowedModules() : body.allowedModules();
        appRegistry.save(appId, apiKey, modules);
        return Map.of("ok", true, "appId", appId);
    }

    /**
     * 删除应用：立即失效
     */
    @DeleteMapping("/apps/{appId}")
    public Map<String, Object> removeApp(@PathVariable String appId) {
        appRegistry.remove(appId);
        return Map.of("ok", true, "appId", appId);
    }

    public record AppRequest(String appId, String apiKey, List<String> allowedModules) {
    }

    public record AppUpdateRequest(String apiKey, List<String> allowedModules) {
    }

    private Map<String, Object> toView(WsClient client, long now, long heartbeatTimeout) {
        long silence = Math.max(now - client.getLastPongTime(), 0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("clientId", client.getClientId());
        view.put("ip", client.getIp() == null ? "-" : client.getIp());
        view.put("modules", client.getModules());
        view.put("createTime", client.getCreateTime());
        view.put("lastPongTime", client.getLastPongTime());
        view.put("lastActiveTime", client.getLastActiveTime());
        view.put("online", Math.max(now - client.getCreateTime(), 0));
        view.put("silence", silence);
        view.put("stale", silence > heartbeatTimeout);
        view.put("uplinkCount", client.getUplinkCount().get());
        view.put("downlinkCount", client.getDownlinkCount().get());
        return view;
    }
}
