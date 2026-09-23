package com.simonking.nexus.websocket.constant;

import io.netty.util.AttributeKey;

import java.util.Set;

/**
 * WebSocket 连接的 Channel 属性键
 *
 * <p>握手阶段写入、后续帧处理器与注册表读取的跨 Handler 载体：
 * Netty 的 pipeline 各 Handler 之间没有共享上下文，属性只能挂在 {@code Channel} 上。
 *
 * <p>单独成类是为了把 Netty 依赖挡在 {@code nexus-common} 之外：
 * {@code WsConstants} 里收的是纯字符串协议常量，{@link AttributeKey} 只有本服务用得到。
 *
 * <p>键名即身份：{@code AttributeKey.valueOf(name)} 按名字池化，同名返回同一个实例，
 * 因此改名等于换键——读写两端必须同时改，否则会静默读到 null。
 *
 * @author simonking
 */
public final class WsChannelKeys {

    private WsChannelKeys() {
    }

    /**
     * 订阅模块集合
     */
    public static final AttributeKey<Set<String>> MODULES = AttributeKey.valueOf("ws.modules");

    /**
     * 客户端IP
     */
    public static final AttributeKey<String> IP = AttributeKey.valueOf("ws.ip");

    /**
     * 客户端ID。握手阶段由服务端生成后一路带到帧处理器，
     * 帧处理器不再从 {@code channel.id()} 反推——那个值随连接变化，扛不住重连
     */
    public static final AttributeKey<String> CLIENT_ID = AttributeKey.valueOf("ws.clientId");

    /**
     * 握手 URI（原样保留，便于排障）
     */
    public static final AttributeKey<String> URI = AttributeKey.valueOf("ws.uri");
}
