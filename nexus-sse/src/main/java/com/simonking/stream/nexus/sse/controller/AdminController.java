package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.sse.auth.PushApp;
import com.simonking.stream.nexus.sse.auth.PushAppRegistry;
import com.simonking.stream.nexus.sse.config.SseProperties;
import com.simonking.stream.nexus.sse.connection.SseClient;
import com.simonking.stream.nexus.sse.connection.SseClientRegistry;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接运维接口（供 {@code /admin.html} 管理页使用）
 *
 * <p>只做两件事：看全量连接的实时状态、强制下线某条连接。
 *
 * @author simonking
 */
@RestController
@RequestMapping("/sse/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SseClientRegistry registry;

    private final PushAppRegistry appRegistry;

    private final SseProperties properties;

    /**
     * 推送应用列表（管理页的应用台账 / 测试页的下拉数据源）
     *
     * <p>返回明文 apiKey 是有意为之：测试页要靠它拼鉴权头。
     * 代价是「拿到这个接口就拿到全部推送权限」，见文档 E15——该接口必须内网隔离。
     */
    @GetMapping("/apps")
    public Map<String, Object> apps() {
        List<PushApp> items = appRegistry.list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("authEnabled", appRegistry.isAuthEnabled());
        result.put("defaultAppId", PushAppRegistry.DEFAULT_APP_ID);
        result.put("items", items);
        return result;
    }

    /**
     * 生成一个随机 apiKey（GUID → Base64），供管理页「自动生成」按钮使用
     */
    @GetMapping("/apps/generate-key")
    public Map<String, Object> generateKey() {
        return Map.of("apiKey", PushAppRegistry.generateApiKey());
    }

    /**
     * 新增应用：同名已存在时拒绝（避免手滑覆盖线上凭证，覆盖请先删除）
     *
     * <p>{@code apiKey} 留空则由服务端生成（GUID → Base64），避免人工起弱口令。
     */
    @PostMapping("/apps")
    public Map<String, Object> addApp(@RequestBody AppRequest body) {
        if (body == null || !StringUtils.hasText(body.appId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appId is required");
        }
        String appId = body.appId().trim();
        if (appRegistry.contains(appId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appId already exists: " + appId);
        }
        String apiKey = StringUtils.hasText(body.apiKey()) ? body.apiKey().trim() : PushAppRegistry.generateApiKey();
        appRegistry.save(new PushApp(appId, apiKey, PushAppRegistry.normalize(body.allowedModules())));
        return Map.of("ok", true, "appId", appId, "apiKey", apiKey);
    }

    /**
     * 修改应用：只改传了的字段，{@code appId} 本身不可改（改标识请删除后重建）
     *
     * <p>{@code apiKey} 为空 / 全空白表示不修改；{@code allowedModules} 为 null 表示不修改，
     * 传空数组表示清空限制（归一化成 {@code *}）。改动立即生效：下一次推送就按新白名单鉴权。
     */
    @PutMapping("/apps/{appId}")
    public Map<String, Object> updateApp(@PathVariable String appId, @RequestBody AppUpdateRequest body) {
        PushApp existed = appRegistry.find(appId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "appId not found: " + appId));
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String apiKey = StringUtils.hasText(body.apiKey()) ? body.apiKey().trim() : existed.apiKey();
        List<String> modules = body.allowedModules() == null
                ? existed.allowedModules()
                : PushAppRegistry.normalize(body.allowedModules());

        PushApp updated = new PushApp(existed.appId(), apiKey, modules);
        appRegistry.save(updated);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("appId", updated.appId());
        result.put("apiKey", updated.apiKey());
        result.put("allowedModules", updated.allowedModules());
        return result;
    }

    /**
     * 删除应用：立即生效，该 appId 之后的推送一律 401
     */
    @DeleteMapping("/apps/{appId}")
    public Map<String, Object> removeApp(@PathVariable String appId) {
        boolean removed = appRegistry.remove(appId);
        return Map.of("ok", true, "appId", appId, "removed", removed);
    }

    /**
     * 连接与业务模块概览
     *
     * <p>额外下发 {@code serverTime} 与 {@code heartbeatTimeout}：管理页据此算「静默时长」和
     * 「疑似失联」，避免依赖浏览器本机时钟（与服务端有偏差时心跳判读会失真）。
     */
    @GetMapping("/connections")
    public Map<String, Object> connections() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> items = registry.all().stream()
                .map(client -> toView(client, now))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", registry.size());
        result.put("modules", registry.moduleStats());
        result.put("serverTime", now);
        result.put("heartbeatTimeout", properties.getHeartbeatTimeout().toMillis());
        result.put("items", items);
        return result;
    }

    /**
     * 强制下线：走统一回收入口，连接主表与模块索引一起清理
     */
    @DeleteMapping("/connections/{clientId}")
    public Map<String, Object> kick(@PathVariable String clientId) {
        registry.remove(clientId);
        return Map.of("ok", true, "clientId", clientId);
    }

    /**
     * 新增应用的请求体：{@code allowedModules} 留空 = 不限制模块
     */
    public record AppRequest(String appId, String apiKey, List<String> allowedModules) {
    }

    /**
     * 修改应用的请求体：字段为 null / 空串表示「不改」，{@code allowedModules} 空数组表示不限模块
     */
    public record AppUpdateRequest(String apiKey, List<String> allowedModules) {
    }

    private Map<String, Object> toView(SseClient client, long now) {
        // 时钟回拨 / 刚建连时可能算出负数，归零
        long silence = Math.max(now - client.getLastPongTime(), 0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("clientId", client.getClientId());
        view.put("ip", client.getIp() == null ? "-" : client.getIp());
        view.put("modules", client.getModules());
        view.put("createTime", client.getCreateTime());
        view.put("lastPongTime", client.getLastPongTime());
        view.put("online", Math.max(now - client.getCreateTime(), 0));
        view.put("silence", silence);
        view.put("stale", silence > properties.getHeartbeatTimeout().toMillis());
        return view;
    }
}
