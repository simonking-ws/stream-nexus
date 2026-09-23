package com.simonking.stream.nexus.common.constant;

import com.simonking.stream.nexus.common.util.IpUtils;
import com.simonking.stream.nexus.common.util.NexusUtils;

/**
 * WebSocket 服务协议层常量
 *
 * <p>跨层出现的字符串（请求头 / 查询参数 / 请求属性 / 系统模块名 / 系统动作名）一律收拢在此，
 * 禁止在业务代码里散落字面量，否则一端改另一端漏改会静默失效。
 *
 * <p>与 SSE 模块的 {@link SseConstants} 是两套独立协议（前缀 ws / X-Ws），
 * 两个服务互不影响，可同时部署。放在同一模块只是为了共用 {@code util} 与模型，
 * 不表示两者有任何运行时耦合。
 *
 * <p>两侧取值相同的常量已上提到 {@link NexusConstants}，这里保留同名引用，调用方无需感知。
 * 本类只放字面量常量，相关行为方法已收进 {@code util} 下的工具类：
 * 客户端ID 生成与全局模块判定见 {@link NexusUtils}，客户端IP 解析见 {@link IpUtils}。
 *
 * <p>Netty 侧的 Channel 属性键不在本类（见 {@code nexus-websocket} 的 {@code WsChannelKeys}）：
 * {@code common} 不引入 Netty，避免给 SSE 这类非 Netty 服务背上依赖。
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
    public static final String GLOBAL_MODULE = NexusConstants.GLOBAL_MODULE;

    /**
     * 应用白名单中的通配模块：表示不限模块
     */
    public static final String MODULE_WILDCARD = NexusConstants.MODULE_WILDCARD;

    /**
     * 系统动作名：建连回执
     */
    public static final String ACTION_CONNECTED = NexusConstants.ACTION_CONNECTED;

    /**
     * 系统动作名：心跳探测（服务端 -> 客户端）
     */
    public static final String ACTION_PING = NexusConstants.ACTION_PING;

    /**
     * 系统动作名：心跳应答（客户端 -> 服务端）
     */
    public static final String ACTION_PONG = NexusConstants.ACTION_PONG;

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
    public static final String PARAM_CONNECT_TOKEN = NexusConstants.PARAM_CONNECT_TOKEN;

    /**
     * 订阅模块 查询参数，逗号分隔
     */
    public static final String PARAM_MODULES = "modules";

    /**
     * 鉴权通过后写入请求属性的「应用可用模块白名单」
     */
    public static final String ATTR_ALLOWED_MODULES = "ws.allowedModules";

    /**
     * 管理员踢下线时使用的 WebSocket 关闭码（4000-4999 为应用层自定义区间）
     */
    public static final int CLOSE_CODE_KICKED = 4000;

    /**
     * 客户端ID 冲突时使用的 WebSocket 关闭码（理论上不会发生，仅作兜底）
     */
    public static final int CLOSE_CODE_DUPLICATE = 4001;
}
