package com.simonking.stream.nexus.common.constant;

import com.simonking.stream.nexus.common.util.IpUtils;
import com.simonking.stream.nexus.common.util.NexusUtils;

/**
 * SSE 协议层常量
 *
 * <p>集中存放**跨层约定的字符串**：这些值同时出现在服务端消息构造、鉴权头、客户端路由判断中，
 * 一旦散落各处，改名时极易漏改且编译器不会报错。修改此处等价于修改协议，需服务端与客户端同步发布。
 *
 * <p>只收协议级 / 系统级字面量，业务模块名（{@code lot} / {@code order}）由业务方自定义，不在此列。
 *
 * <p><b>两侧取值相同的常量一律直接用 {@link NexusConstants}（全局模块、白名单通配、建连令牌参数、
 * 系统动作、代理头），本类不再重复定义同名别名</b>——别名只会让人分不清该改哪一处。
 *
 * <p>本类只放字面量常量，相关行为方法已收进 {@code util} 下的工具类：
 * 客户端ID 生成与全局模块判定见 {@link NexusUtils}，客户端IP 解析见 {@link IpUtils}。
 *
 * @author simonking
 */
public final class SseConstants {

    private SseConstants() {
    }

    /**
     * 客户端ID 参数名 / 字段标识（{@code clientId}）
     *
     * <p>只出现在两处：心跳应答的查询参数、建连回执与运维台账里的字段名。
     * 参数名硬编码在多处，改名时编译器不会报错，故收拢在此。
     */
    public static final String PARAM_CLIENT_ID = "clientId";

    /**
     * 系统内部消息使用的业务模块名。
     *
     * <p>心跳、建连通知等系统消息都挂在这个模块下，客户端路由表不匹配即忽略，
     * 因此**业务方不得占用**该模块名（订阅、推送均会与系统消息混淆）。
     */
    public static final String SYS_MODULE = "sse";

    /**
     * REST 推送接口路径：业务系统推消息的唯一入口
     *
     * <p>客户端 SDK 的 {@code ssePush} 拼请求地址时取自这里，与下发的 {@link #HTTP_PORT} 一起构成默认地址，
     * 避免 SDK 把路径写死后服务端改不动。
     */
    public static final String PUSH_PATH = "/sse/push";

    /**
     * REST 推送默认 HTTP 端口（{@code server.port}）
     *
     * <p>SSE 与 WebSocket 是两个独立服务、各占一个端口（8088 / 8089），
     * SDK 的两个推送方法各自取各自的默认值。
     */
    public static final int HTTP_PORT = 8088;

    /**
     * 推送鉴权请求头：应用标识（{@code X-Sse-AppId}）
     *
     * <p>用于**定位调用方**，服务端按它反查该应用允许推送的业务模块（白名单归属应用）。
     * 本身不是秘密，但不能单独通过鉴权——必须与 {@link #HEADER_API_KEY} 配对。
     */
    public static final String HEADER_APP_ID = "X-Sse-AppId";

    /**
     * 推送鉴权请求头：应用密钥（{@code X-Sse-Key}）
     *
     * <p>用于**证明调用方身份**，与 {@link #HEADER_APP_ID} 声明的应用必须匹配，
     * 否则视为未授权。泄露即等于交出该应用的推送权限。
     */
    public static final String HEADER_API_KEY = "X-Sse-Key";

    /**
     * 建连鉴权请求头：连接令牌（{@code X-Sse-Token}）
     *
     * <p>仅在服务端开启建连鉴权时生效，与 {@link NexusConstants#PARAM_CONNECT_TOKEN} 二选一。
     * 浏览器 {@code EventSource} 无法设置自定义请求头，此时只能用查询参数。
     */
    public static final String HEADER_CONNECT_TOKEN = "X-Sse-Token";

    /**
     * 鉴权通过后，该密钥允许推送的业务模块写入 {@code HttpServletRequest} 的属性名
     * （供下游做模块白名单校验）
     */
    public static final String ATTR_ALLOWED_MODULES = "sse.allowedModules";
}
