package com.simonking.nexus.websocket.controller;

import com.simonking.nexus.websocket.auth.PushApp;
import com.simonking.nexus.websocket.auth.PushAppRegistry;
import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.WsConstants;
import com.simonking.stream.nexus.common.location.IpLocationService;
import com.simonking.nexus.websocket.model.TcpClient;
import com.simonking.nexus.websocket.model.WsClient;
import com.simonking.nexus.websocket.registry.TcpClientRegistry;
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

    /**
     * 台账里的协议标识：页面据此区分一条连接是终端的 WebSocket 长连接，
     * 还是业务系统侧的 TCP 长连接
     */
    private static final String PROTOCOL_WS = "WS";
    private static final String PROTOCOL_TCP = "TCP";

    private final WsClientRegistry registry;

    private final PushAppRegistry appRegistry;

    private final WsProperties properties;

    private final TcpClientRegistry tcpRegistry;

    /**
     * IP 归属地（城市）：台账里给客户端 IP 补一行来源，缺库时返回 {@code -}
     */
    private final IpLocationService ipLocation;

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
        // 协议标识 + TCP 侧的口径：概览要给出「WS / TCP」构成，页面不必为看一眼再拉一次 TCP 接口
        result.put("protocol", PROTOCOL_WS);
        result.put("tcpPort", properties.getTcpPort());
        result.put("tcpTotal", tcpRegistry.size());
        result.put("items", items);
        return result;
    }

    /**
     * TCP 接入连接台账：哪些业务系统正连着、推了多少条
     *
     * <p>与终端台账分开：两者数量级差三个数量级，混在一张表里看不清，
     * 而且「连接数异常增长」的告警含义完全不同（终端涨是正常流量，TCP 涨多半是客户端重连逻辑写错）。
     */
    @GetMapping("/tcp/connections")
    public Map<String, Object> tcpConnections() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> items = new ArrayList<>();
        for (TcpClient client : tcpRegistry.all()) {
            items.add(toTcpView(client, now));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", PROTOCOL_TCP);
        result.put("serverTime", now);
        result.put("total", tcpRegistry.size());
        result.put("tcpPort", properties.getTcpPort());
        result.put("maxConnections", properties.getMaxConnections());
        result.put("heartbeatInterval", properties.getHeartbeatInterval().toMillis());
        result.put("heartbeatTimeout", properties.getHeartbeatTimeout().toMillis());
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
     *
     * <p>内置默认应用同名一律拒绝：它始终在台账里且只读，要别的凭证（别的 appId / key / 白名单）
     * 就新建应用，而不是改它。
     *
     * <p>apiKey 留空时由服务端生成高强度随机串（UUID 128bit → Base64Url，22 字符），
     * 不自己想口令；返回值带上最终落地的 apiKey，页面回显后可直接复制。
     */
    @PostMapping("/apps")
    public Map<String, Object> addApp(@RequestBody AppRequest body) {
        if (body == null || !StringUtils.hasText(body.appId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appId is required");
        }
        if (PushAppRegistry.isBuiltIn(body.appId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "内置默认应用已存在且只读，请换一个 appId：" + NexusConstants.DEFAULT_APP_ID);
        }
        if (appRegistry.contains(body.appId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "appId already exists: " + body.appId());
        }
        String appId = body.appId().trim();
        // 生成放在服务端：浏览器的随机强度不可控，且同一个 key 只应在这里产生一次
        String apiKey = StringUtils.hasText(body.apiKey())
                ? body.apiKey().trim()
                : PushAppRegistry.generateApiKey();
        appRegistry.save(appId, apiKey, body.allowedModules());
        PushApp saved = appRegistry.find(appId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("appId", appId);
        result.put("apiKey", saved == null ? apiKey : saved.apiKey());
        result.put("allowedModules", saved == null ? List.of() : saved.allowedModules());
        return result;
    }

    /**
     * 修改应用：字段为 null / 空串表示不修改；改动落盘，重启后仍在
     *
     * <p>内置默认应用例外：整条只读，appId / apiKey / 白名单 都改不动（有改动 → 409）；
     * 传回来的值与现状完全一致则视为「没改」，正常返回（幂等，不报错）。
     */
    @PutMapping("/apps/{appId}")
    public Map<String, Object> updateApp(@PathVariable String appId, @RequestBody AppUpdateRequest body) {
        PushApp existed = appRegistry.find(appId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "app not found: " + appId));
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        // 内置默认应用只读：键和值都写死在代码里，改了等于把联调与文档的基准改掉，
        // 且所有接入方都要跟着换——要别的凭证就新建应用
        String apiKey = StringUtils.hasText(body.apiKey()) ? body.apiKey().trim() : existed.apiKey();
        List<String> modules = body.allowedModules() == null ? existed.allowedModules() : body.allowedModules();
        if (PushAppRegistry.isBuiltIn(appId)) {
            boolean keyUnchanged = NexusConstants.DEFAULT_API_KEY.equals(apiKey);
            boolean modulesUnchanged = PushAppRegistry.normalize(modules)
                    .equals(PushAppRegistry.DEFAULT_ALLOWED_MODULES);
            if (!keyUnchanged || !modulesUnchanged) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "内置默认应用只读，appId / apiKey / 白名单 均不可修改：" + NexusConstants.DEFAULT_APP_ID);
            }
            // 值完全一致 → 视为没改，幂等返回（页面不会对它发编辑请求，这里防御性兜住）
            return appView(existed);
        }
        appRegistry.save(appId, apiKey, modules);
        // 回读服务端最终值：白名单已做归一化（空 → *），回显给页面才不会和实际台账不一致
        return appView(appRegistry.find(appId).orElse(existed));
    }

    /** 应用对象的统一出参形态：无论新增 / 修改，页面拿到的字段都齐 */
    private Map<String, Object> appView(PushApp app) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("appId", app.appId());
        result.put("apiKey", app.apiKey());
        result.put("allowedModules", app.allowedModules() == null ? List.of() : app.allowedModules());
        return result;
    }

    /**
     * 删除应用：立即失效，该 appId 之后的推送一律 401；并同步落盘，重启不会自己回来
     *
     * <p>内置默认应用例外：始终存在且只读，删它返回 409（页面也不给删除 / 修改按钮）。
     * 要别的凭证就新建应用，而不是改它、删它。
     */
    @DeleteMapping("/apps/{appId}")
    public Map<String, Object> removeApp(@PathVariable String appId) {
        if (PushAppRegistry.isBuiltIn(appId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "内置默认应用不可删除（只读凭证）：" + NexusConstants.DEFAULT_APP_ID);
        }
        boolean removed = appRegistry.remove(appId);
        return Map.of("ok", true, "appId", appId, "removed", removed);
    }

    public record AppRequest(String appId, String apiKey, List<String> allowedModules) {
    }

    public record AppUpdateRequest(String apiKey, List<String> allowedModules) {
    }

    private Map<String, Object> toTcpView(TcpClient client, long now) {
        long silence = Math.max(now - client.getLastPongTime(), 0);
        Map<String, Object> view = new LinkedHashMap<>();
        // 协议标识：TCP 台账与终端台账字段不同（通道ID / 报文数 vs 客户端ID / 订阅模块），
        // 前端合并展示或筛选时靠它区分
        view.put("protocol", PROTOCOL_TCP);
        view.put("channelId", client.getChannelId());
        view.put("ip", client.getIp() == null ? "-" : client.getIp());
        view.put("createTime", client.getCreateTime());
        view.put("lastActiveTime", client.getLastActiveTime());
        view.put("lastPongTime", client.getLastPongTime());
        view.put("online", Math.max(now - client.getCreateTime(), 0));
        view.put("silence", silence);
        view.put("stale", silence > properties.getHeartbeatTimeout().toMillis());
        view.put("msgCount", client.getMsgCount().get());
        return view;
    }

    private Map<String, Object> toView(WsClient client, long now, long heartbeatTimeout) {
        long silence = Math.max(now - client.getLastPongTime(), 0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("protocol", PROTOCOL_WS);
        view.put("clientId", client.getClientId());
        view.put("ip", client.getIp() == null ? "-" : client.getIp());
        view.put("city", ipLocation.city(client.getIp()));
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
