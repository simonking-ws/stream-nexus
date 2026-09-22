package com.simonking.nexus.websocket.constant;

import io.netty.util.AttributeKey;

import java.util.Set;
import java.util.UUID;

/**
 * WebSocket 服务协议层常量
 *
 * <p>跨层出现的字符串（请求头 / 查询参数 / 请求属性 / 系统模块名 / 系统动作名）一律收拢在此，
 * 禁止在业务代码里散落字面量，否则一端改另一端漏改会静默失效。
 *
 * <p>与 SSE 模块的 {@code SseConstants} 是两套独立协议（前缀 ws / X-Ws），
 * 两个服务互不影响，可同时部署。
 *
 * @author simonking
 */
public final class WsConstants {

    private WsConstants() {
    }

    /**
     * 系统模块名：服务端主动下发的消息（建连回执、心跳、踢下线通知）都用它，
     * 业务模块禁止使用该值，否则客户端无法区分「系统消息」与「业务消息」
     */
    public static final String SYS_MODULE = "ws";

    /**
     * 全局模块：订阅它 = 订阅所有模块
     */
    public static final String GLOBAL_MODULE = "*";

    /**
     * 应用白名单中的通配模块：表示不限模块
     */
    public static final String MODULE_WILDCARD = "*";

    /**
     * 系统动作名：建连回执
     */
    public static final String ACTION_CONNECTED = "connected";

    /**
     * 系统动作名：心跳探测（服务端 -> 客户端）
     */
    public static final String ACTION_PING = "ping";

    /**
     * 系统动作名：心跳应答（客户端 -> 服务端）
     */
    public static final String ACTION_PONG = "pong";

    /**
     * 系统动作名：上行消息回显（测试页用）
     */
    public static final String ACTION_ECHO = "echo";

    /**
     * 系统动作名：被管理员踢下线
     */
    public static final String ACTION_KICKED = "kicked";

    /**
     * REST 推送接口路径：业务系统推消息的唯一入口（与 WS 建连走同一台机器的不同端口）
     */
    public static final String PUSH_PATH = "/ws/push";

    /**
     * 推送应用ID 请求头
     */
    public static final String HEADER_APP_ID = "X-Ws-AppId";

    /**
     * 推送应用密钥 请求头
     */
    public static final String HEADER_API_KEY = "X-Ws-Key";

    /**
     * 建连令牌 查询参数（WebSocket 握手无法自定义请求头，只能走查询参数）
     */
    public static final String PARAM_CONNECT_TOKEN = "token";

    /**
     * 订阅模块 查询参数，逗号分隔
     */
    public static final String PARAM_MODULES = "modules";

    /**
     * 代理链头：反向代理后 TCP 对端地址只会是网关地址，真实客户端要靠它
     */
    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * 代理链头（单层）：X-Forwarded-For 取不到时的兜底
     */
    public static final String HEADER_X_REAL_IP = "X-Real-IP";

    /**
     * 鉴权通过后写入请求属性的「应用可用模块白名单」
     */
    public static final String ATTR_ALLOWED_MODULES = "ws.allowedModules";

    /**
     * Channel 属性：订阅模块集合
     */
    public static final AttributeKey<Set<String>> ATTR_MODULES = AttributeKey.valueOf("ws.modules");

    /**
     * Channel 属性：客户端IP
     */
    public static final AttributeKey<String> ATTR_IP = AttributeKey.valueOf("ws.ip");

    /**
     * Channel 属性：客户端ID。握手阶段由服务端生成后一路带到帧处理器，
     * 帧处理器不再从 {@code channel.id()} 反推——那个值随连接变化，扛不住重连
     */
    public static final AttributeKey<String> ATTR_CLIENT_ID = AttributeKey.valueOf("ws.clientId");

    /**
     * Channel 属性：握手 URI（原样保留，便于排障）
     */
    public static final AttributeKey<String> ATTR_URI = AttributeKey.valueOf("ws.uri");

    /**
     * 管理员踢下线时使用的 WebSocket 关闭码（4000-4999 为应用层自定义区间）
     */
    public static final int CLOSE_CODE_KICKED = 4000;

    /**
     * 客户端ID 冲突时使用的 WebSocket 关闭码（理论上不会发生，仅作兜底）
     */
    public static final int CLOSE_CODE_DUPLICATE = 4001;

    /**
     * 生成一个客户端ID：UUID v4（36 字符，形如 {@code 6f1d2a3c-...}）
     *
     * <p>由<b>服务端</b>在握手阶段生成，客户端不需要（也不允许）自带：
     * 让客户端自带ID 意味着客户端可以声明任意身份，服务端要么承担被冒用的风险，
     * 要么再叠一层令牌校验；交给服务端生成则没有这些问题。
     *
     * <p>UUID 足够长（122 位随机），即便海量终端并发建连碰撞概率也可以忽略——
     * 不像 {@code channel.id().asShortText()} 那种 32 位哈希，几万条连接就会撞。
     * 代价是它<b>随连接生命周期变化</b>（重连即换）：要按用户维度稳定寻址，请用模块订阅
     * 或在业务系统侧维护「用户 → 当前 clientId」的映射（客户端建连后上报）。
     */
    public static String newClientId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 判断是否全局模块：{@code *} / {@code Global} 等写法都算，避免大小写与写法差异
     */
    public static boolean isGlobal(String module) {
        return module != null && GLOBAL_MODULE.equalsIgnoreCase(module.trim());
    }
}
