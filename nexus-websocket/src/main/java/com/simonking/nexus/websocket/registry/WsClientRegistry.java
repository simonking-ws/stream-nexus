package com.simonking.nexus.websocket.registry;

import com.simonking.nexus.websocket.model.WsClient;
import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.WsConstants;
import com.simonking.stream.nexus.common.util.NexusUtils;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接注册表
 *
 * <p>两张表：主表（clientId -> 连接）+ 模块倒排索引（模块 -> clientId 集合）。
 * 推送按模块命中时，倒排索引把 O(n) 全表扫描降为 O(命中数)。
 *
 * <p>主键是<b>客户端ID</b>（{@link NexusUtils#newClientId()}）：由客户端自带或服务端兜底生成的
 * UUID，与 {@code nexus-sse} 的 {@code clientId} 同一套语义，业务系统可以按它定向推送。
 * 它不随重连变化（前提是客户端自己带着同一个ID来），这是它优于「通道ID」的地方。
 *
 * <p>同一个 clientId 重复建连会被拒绝：要么是客户端 bug（两个标签页用了同一个持久化ID），
 * 要么是有人在冒用他人ID。放行会让后一条连接静默顶掉前一条，前一条从此收不到消息还毫不知情。
 *
 * <p>所有删除必须走 {@link #remove(String)}：它会同时清倒排索引并关闭 channel。
 * 旁路删除（直接操作 map）会留下悬空的倒排索引条目，表现为「推给一个已下线的连接」。
 *
 * @author simonking
 */
@Slf4j
@Component
public class WsClientRegistry {

    private final Map<String, WsClient> clients = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> moduleIndex = new ConcurrentHashMap<>();

    public boolean contains(String clientId) {
        return clients.containsKey(clientId);
    }

    public int size() {
        return clients.size();
    }

    public WsClient get(String clientId) {
        return clients.get(clientId);
    }

    public Collection<WsClient> all() {
        return clients.values();
    }

    /**
     * 登记连接；clientId 已在线时返回 null（调用方应关闭这条连接）
     */
    public WsClient register(String clientId, Channel channel, Set<String> modules, String ip, String uri) {
        WsClient client = new WsClient(clientId, channel, modules, ip, uri);
        if (clients.putIfAbsent(clientId, client) != null) {
            return null;
        }
        for (String module : modules) {
            moduleIndex.computeIfAbsent(module, k -> ConcurrentHashMap.newKeySet()).add(clientId);
        }
        return client;
    }

    /**
     * 按模块取连接：全局模块订阅者也会命中
     */
    public Collection<WsClient> byModule(String module) {
        Set<String> ids = moduleIndex.get(module);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, WsClient> result = new LinkedHashMap<>();
        for (String id : ids) {
            WsClient client = clients.get(id);
            if (client != null) {
                result.put(id, client);
            }
        }
        return result.values();
    }

    /**
     * 按客户端ID 列表定向取连接
     */
    public Collection<WsClient> byClientIds(Collection<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, WsClient> result = new LinkedHashMap<>();
        for (String id : clientIds) {
            WsClient client = clients.get(id);
            if (client != null) {
                result.put(id, client);
            }
        }
        return result.values();
    }

    /**
     * 模块分布统计，供管理界面展示
     */
    public Map<String, Integer> moduleStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        moduleIndex.forEach((module, ids) -> stats.put(module, ids.size()));
        return stats;
    }

    /**
     * 统一回收入口：清倒排索引 + 关闭连接
     */
    public void remove(String clientId) {
        WsClient client = clients.remove(clientId);
        if (client == null) {
            return;
        }
        for (String module : client.getModules()) {
            moduleIndex.computeIfPresent(module, (k, ids) -> {
                ids.remove(clientId);
                return ids.isEmpty() ? null : ids;
            });
        }
        closeQuietly(client.getChannel());
    }

    /**
     * 管理员强制下线：先给客户端一个可读的关闭码，再关闭连接
     */
    public void kick(String clientId, String reason) {
        WsClient client = clients.get(clientId);
        if (client == null) {
            return;
        }
        try {
            client.getChannel().writeAndFlush(new CloseWebSocketFrame(WsConstants.CLOSE_CODE_KICKED, reason));
        } catch (Exception ignored) {
            // 通道可能已失效，兜底走 remove 关闭
        }
        remove(clientId);
    }

    /**
     * 向单个连接下发文本帧；失败（连接已失效）时顺便回收
     *
     * @return 是否提交成功
     */
    public boolean send(String clientId, String payload) {
        WsClient client = clients.get(clientId);
        if (client == null) {
            return false;
        }
        try {
            client.getChannel().writeAndFlush(new TextWebSocketFrame(payload));
            client.getDownlinkCount().incrementAndGet();
            return true;
        } catch (Exception e) {
            log.warn("[ws] send failed, clientId={}", clientId, e);
            remove(clientId);
            return false;
        }
    }

    private void closeQuietly(Channel channel) {
        try {
            if (channel != null && channel.isActive()) {
                channel.close();
            }
        } catch (Exception ignored) {
            // 关闭失败无需处理：channelInactive 不会再触发
        }
    }

    /**
     * 订阅模块集合的规范化：去重 + 保序 + 全局写法归一
     */
    public static Set<String> normalizeModules(Collection<String> modules) {
        Set<String> result = new LinkedHashSet<>();
        if (modules != null) {
            for (String module : modules) {
                if (module == null) {
                    continue;
                }
                String trimmed = module.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                result.add(NexusUtils.isGlobal(trimmed) ? NexusConstants.GLOBAL_MODULE : trimmed);
            }
        }
        if (result.isEmpty()) {
            result.add(NexusConstants.GLOBAL_MODULE);
        }
        return result;
    }
}
