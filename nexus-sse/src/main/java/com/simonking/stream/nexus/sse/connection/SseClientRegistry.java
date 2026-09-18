package com.simonking.stream.nexus.sse.connection;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接注册表
 *
 * <p>维护两张表：
 * <ul>
 *     <li>{@code clientId -> SseClient}：主表</li>
 *     <li>{@code bizModule -> Set<clientId>}：业务模块二级索引，按模块推送时 O(1) 定位</li>
 * </ul>
 *
 * <p>所有回收路径必须走 {@link #remove(String)}，禁止旁路删除，否则模块索引会泄漏。
 *
 * @author simonking
 */
@Component
public class SseClientRegistry {

    private final Map<String, SseClient> clients = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> moduleIndex = new ConcurrentHashMap<>();

    /**
     * 注册连接
     *
     * @return false 表示 clientId 已存在
     */
    public boolean add(SseClient client) {
        if (clients.putIfAbsent(client.getClientId(), client) != null) {
            return false;
        }
        for (String module : client.getModules()) {
            moduleIndex.computeIfAbsent(module, k -> ConcurrentHashMap.newKeySet()).add(client.getClientId());
        }
        return true;
    }

    public SseClient get(String clientId) {
        return clients.get(clientId);
    }

    public Collection<SseClient> all() {
        return new ArrayList<>(clients.values());
    }

    public int size() {
        return clients.size();
    }

    /**
     * 按业务模块取连接
     */
    public Collection<SseClient> byModule(String module) {
        if (module == null || module.isBlank()) {
            return List.of();
        }
        Set<String> ids = moduleIndex.get(module);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<SseClient> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            SseClient client = clients.get(id);
            if (client != null) {
                result.add(client);
            }
        }
        return result;
    }

    /**
     * 按客户端ID取连接（定向推送）
     */
    public List<SseClient> byClientIds(List<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return List.of();
        }
        List<SseClient> result = new ArrayList<>(clientIds.size());
        for (String id : clientIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            SseClient client = clients.get(id);
            if (client != null) {
                result.add(client);
            }
        }
        return result;
    }

    /**
     * 统一回收入口：清理模块索引 + 关闭 emitter
     */
    public void remove(String clientId) {
        SseClient client = clients.remove(clientId);
        if (client == null) {
            return;
        }
        for (String module : client.getModules()) {
            moduleIndex.computeIfPresent(module, (k, ids) -> {
                ids.remove(clientId);
                return ids.isEmpty() ? null : ids;
            });
        }
        try {
            client.getEmitter().complete();
        } catch (Exception ignored) {
            // 连接可能已关闭，忽略
        }
    }

    /**
     * 业务模块连接数统计
     */
    public Map<String, Integer> moduleStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        moduleIndex.forEach((module, ids) -> stats.put(module, ids.size()));
        return stats;
    }
}
