package com.simonking.stream.nexus.common.constant;

import java.util.UUID;

/**
 * SSE 协议层常量
 *
 * <p>集中存放**跨层约定的字符串**：这些值同时出现在服务端消息构造、鉴权头、客户端路由判断中，
 * 一旦散落各处，改名时极易漏改且编译器不会报错。修改此处等价于修改协议，需服务端与客户端同步发布。
 *
 * <p>只收协议级 / 系统级字面量，业务模块名（{@code lot} / {@code order}）由业务方自定义，不在此列。
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
     * 全局模块名（{@code *}）：订阅 {@code modules} 为空时的默认值，同时是一条「全量通道」。
     *
     * <p>双向生效：
     * <ul>
     *     <li>订阅侧：{@code modules} 缺省或为空白时，默认订阅该模块；</li>
     *     <li>推送侧：以它为 {@code bizModule} 时广播给全部在线连接；
     *               以其它模块推送时，订阅它的连接同样会收到（每次按模块推送都带上 {@code *}）。</li>
     * </ul>
     *
     * <p>例外：纯定向推送（只填 {@code clientIds}、无 {@code bizModule}）不扩散给 {@code *} 订阅者——
     * 一对一消息不应泄露给无关连接。
     *
     * <p>保留名：业务方不得用 {@code *} 命名自己的业务模块，否则会与上述规则混淆。
     * 它与 {@link #MODULE_WILDCARD} 同形（都是 {@code *}）但**命名空间不同**：
     * 这里出现在「模块名」位置，那里出现在「模块白名单」位置（表示不限模块）。
     */
    public static final String GLOBAL_MODULE = "*";

    /**
     * 生成一个客户端ID：UUID v4（36 字符，形如 {@code 6f1d2a3c-...}）
     *
     * <p>由<b>服务端</b>在建连时生成，客户端不需要（也不允许）自带：
     * 让客户端自带ID 意味着客户端可以声明任意身份，服务端要么承担被冒用的风险，
     * 要么再叠一层令牌校验；交给服务端生成则没有这些问题。
     *
     * <p>UUID 足够长（122 位随机），海量终端并发建连的碰撞概率也可以忽略，
     * 因此 {@code SseClientRegistry#add} 的「ID 冲突」分支只是理论兜底，正常路径不会走到。
     *
     * <p>代价是它<b>随连接生命周期变化</b>（重连即换）：要按用户维度稳定寻址，请用模块订阅
     * 或在业务系统侧维护「用户 → 当前 clientId」的映射（客户端建连后上报）。
     *
     * <p>与 nexus-websocket 的 {@code WsConstants#newClientId()} 同口径。
     */
    public static String newClientId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 判断模块名是否为全局模块（忽略大小写，便于订阅侧归一化后与倒排索引对齐）
     */
    public static boolean isGlobal(String module) {
        return module != null && GLOBAL_MODULE.equalsIgnoreCase(module.trim());
    }

    /**
     * 系统动作：建连成功通知（{@link com.simonking.stream.nexus.common.enums.EventEnum#MESSAGE}）
     *
     * <p>客户端收到后必须重新拉取全量业务状态——服务端无快照、无补发。
     */
    public static final String ACTION_CONNECTED = "connected";

    /**
     * 系统动作：心跳探测（{@link com.simonking.stream.nexus.common.enums.EventEnum#PING}）
     */
    public static final String ACTION_PING = "ping";

    /**
     * 系统动作：心跳应答（{@link com.simonking.stream.nexus.common.enums.EventEnum#PONG}）
     */
    public static final String ACTION_PONG = "pong";

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
     * <p>仅在服务端开启建连鉴权时生效，与 {@link #PARAM_CONNECT_TOKEN} 二选一。
     * 浏览器 {@code EventSource} 无法设置自定义请求头，此时只能用查询参数。
     */
    public static final String HEADER_CONNECT_TOKEN = "X-Sse-Token";

    /**
     * 建连鉴权查询参数名（{@code token}）
     *
     * <p>为 {@code EventSource} 这类无法带自定义头的客户端准备的传参通道；
     * 服务端优先读 {@link #HEADER_CONNECT_TOKEN}，读不到再回退到该参数。
     */
    public static final String PARAM_CONNECT_TOKEN = "token";

    /**
     * 鉴权通过后，该密钥允许推送的业务模块写入 {@code HttpServletRequest} 的属性名
     * （供下游做模块白名单校验）
     */
    public static final String ATTR_ALLOWED_MODULES = "sse.allowedModules";

    /**
     * 模块白名单通配符：不限制可推送的业务模块
     *
     * <p>与 {@link #GLOBAL_MODULE} 同形（都是 {@code *}），但只出现在「应用可推送模块白名单」里，
     * 语义是「不限模块」，不是「只能推全局模块」。两者各自判断、互不复用。
     */
    public static final String MODULE_WILDCARD = "*";
}
