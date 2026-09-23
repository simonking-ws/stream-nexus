package com.simonking.stream.nexus.common.constant;

import com.simonking.stream.nexus.common.util.IpUtils;

/**
 * SSE / WebSocket 共用的协议常量
 *
 * <p>两个服务的协议前缀不同（{@code sse / X-Sse} 与 {@code ws / X-Ws}），
 * 各自的前缀化常量仍留在 {@link SseConstants} / {@link WsConstants}；
 * 这里只收<b>两侧取值完全相同</b>的那部分——它们一旦出现分歧就会静默失效：
 * 订阅侧归一化与推送侧路由对不上、两个服务的台账或鉴权参数对不上，且编译器不会报错。
 *
 * <p>因此改这里等价于同时改两个协议，需两个服务与各自客户端同步发布。
 *
 * @author simonking
 */
public final class NexusConstants {

    private NexusConstants() {
    }

    /**
     * 全局模块名（{@code *}）：订阅它 = 订阅所有模块，推它 = 广播给全部在线连接
     *
     * <p>保留名：业务方不得用 {@code *} 命名自己的业务模块，否则会与「全量通道」规则混淆。
     *
     * <p>它与 {@link #MODULE_WILDCARD} 同形（都是 {@code *}）但<b>命名空间不同</b>：
     * 这里出现在「模块名」位置，那里出现在「模块白名单」位置（表示不限模块），两者各自判断、不可混用。
     */
    public static final String GLOBAL_MODULE = "*";

    /**
     * 模块白名单通配符：不限制可推送的业务模块
     *
     * <p>只出现在「应用可推送模块白名单」里，语义是「不限模块」，不是「只能推全局模块」。
     */
    public static final String MODULE_WILDCARD = "*";

    /**
     * 建连鉴权查询参数名（{@code token}）
     *
     * <p>为无法带自定义请求头的客户端准备的传参通道（浏览器 {@code EventSource} 与
     * {@code WebSocket} 都属于这一类），服务端优先读对应的令牌头，读不到再回退到该参数。
     */
    public static final String PARAM_CONNECT_TOKEN = "token";

    /**
     * 系统动作：建连成功通知
     *
     * <p>客户端收到后必须重新拉取全量业务状态——服务端无快照、无补发。
     */
    public static final String ACTION_CONNECTED = "connected";

    /**
     * 系统动作：心跳探测（服务端 -> 客户端）
     */
    public static final String ACTION_PING = "ping";

    /**
     * 系统动作：心跳应答（客户端 -> 服务端）
     */
    public static final String ACTION_PONG = "pong";

    /**
     * 代理链头：反向代理后 TCP 对端地址只会是网关地址，真实客户端要靠它
     *
     * <p>解析走 {@link IpUtils#resolve(String, String, String)}，两个服务同口径。
     */
    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * 代理链头（单层）：{@link #HEADER_X_FORWARDED_FOR} 取不到时的兜底
     */
    public static final String HEADER_X_REAL_IP = "X-Real-IP";
}
