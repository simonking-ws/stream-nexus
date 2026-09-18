package com.simonking.stream.nexus.sse.controller;

import com.simonking.stream.nexus.sse.connection.SseClient;
import com.simonking.stream.nexus.sse.connection.SseClientRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接运维接口
 *
 * @author simonking
 */
@RestController
@RequestMapping("/sse/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SseClientRegistry registry;

    /**
     * 连接与业务模块概览
     */
    @GetMapping("/connections")
    public Map<String, Object> connections() {
        List<Map<String, Object>> items = registry.all().stream()
                .map(this::toView)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", registry.size());
        result.put("modules", registry.moduleStats());
        result.put("items", items);
        return result;
    }

    /**
     * 强制下线
     */
    @DeleteMapping("/connections/{clientId}")
    public Map<String, Object> kick(@PathVariable String clientId) {
        registry.remove(clientId);
        return Map.of("ok", true, "clientId", clientId);
    }

    private Map<String, Object> toView(SseClient client) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("clientId", client.getClientId());
        view.put("modules", client.getModules());
        view.put("createTime", client.getCreateTime());
        view.put("lastPongTime", client.getLastPongTime());
        return view;
    }
}
