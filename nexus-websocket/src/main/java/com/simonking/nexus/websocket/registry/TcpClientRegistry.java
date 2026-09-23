package com.simonking.nexus.websocket.registry;

import com.simonking.nexus.websocket.model.TcpClient;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCP 接入连接注册表
 *
 * <p>只有一张表（通道ID -> 连接），不需要 {@link WsClientRegistry} 那种模块倒排索引：
 * 业务系统接入连接不订阅任何模块，推送永远不会「按模块命中到某条 TCP 连接」。
 *
 * <p>主键是<b>通道ID</b>（{@code channel.id().asShortText()}）：TCP 侧不做应用鉴权，
 * 也就没有稳定的业务身份可当主键，重连换 ID 无所谓。
 *
 * <p>所有删除必须走 {@link #remove(Channel)}，否则连接数上限的判断会失真（名额被已死连接占住）。
 *
 * @author simonking
 */
@Slf4j
@Component
public class TcpClientRegistry {

    private final Map<String, TcpClient> clients = new ConcurrentHashMap<>();

    public int size() {
        return clients.size();
    }

    public TcpClient get(String channelId) {
        return channelId == null ? null : clients.get(channelId);
    }

    public Collection<TcpClient> all() {
        return Collections.unmodifiableCollection(clients.values());
    }

    /**
     * 登记连接：连接建立即可推送（无鉴权），占名额也从这一刻开始
     */
    public TcpClient register(Channel channel, String ip) {
        String channelId = channel.id().asShortText();
        TcpClient client = new TcpClient(channelId, channel, ip);
        clients.put(channelId, client);
        return client;
    }

    /**
     * 统一回收入口
     */
    public void remove(Channel channel) {
        if (channel == null) {
            return;
        }
        TcpClient removed = clients.remove(channel.id().asShortText());
        if (removed == null) {
            return;
        }
        closeQuietly(channel);
    }

    private void closeQuietly(Channel channel) {
        try {
            if (channel.isActive()) {
                channel.close();
            }
        } catch (Exception e) {
            log.warn("[tcp] 关闭连接异常, channelId={}", channel.id().asShortText(), e);
        }
    }
}
