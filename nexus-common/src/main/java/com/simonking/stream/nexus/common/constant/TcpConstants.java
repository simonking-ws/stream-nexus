package com.simonking.stream.nexus.common.constant;

/**
 * TCP 接入通道的协议常量（业务系统 --TCP--> 推送服务）
 *
 * <p>帧格式、报文里的动作名，以及<b>两端必须一致的默认数值</b>都收在此处：
 * 客户端 SDK 与服务端必须按同一份定义编码，改这里等价于改协议，两端要同步发布。
 *
 * <p><b>为什么帧头用长度字段而不是分隔符</b>：报文体是 JSON，业务 {@code data} 里可以出现任意字符，
 * 用 {@code _} / {@code \n} 之类的分隔符切帧，一旦业务数据里出现同字符就会把一个报文切成两半，
 * 且这种故障只在特定业务数据下偶发，极难复现。长度前缀（4 字节大端）与报文内容无关，天然免疫，
 * 代价只是每帧多 4 字节。
 *
 * <p>与 {@link WsConstants} / {@link SseConstants} 是三条相互独立的链路，互不耦合：
 * TCP 只解决「业务系统怎么把消息送进来」，终端侧协议不受影响。
 *
 * @author simonking
 */
public final class TcpConstants {

    private TcpConstants() {
    }

    /**
     * 系统模块名：TCP 通道自身的控制类消息（推送结果、心跳、错误）都用它
     *
     * <p>业务系统禁止拿它当 {@code bizModule} 推送——那是路由键，会与通道控制消息混淆。
     */
    public static final String SYS_MODULE = "tcp";

    /**
     * 系统动作名：推送请求
     */
    public static final String ACTION_PUSH = "push";

    /**
     * 系统动作名：推送结果回执
     */
    public static final String ACTION_RESULT = "result";

    /**
     * 系统动作名：报文错误 / 业务校验不通过
     */
    public static final String ACTION_ERROR = "error";

    /**
     * 长度字段在帧中的偏移量：报文最前面就是长度
     */
    public static final int LENGTH_FIELD_OFFSET = 0;

    /**
     * 长度字段字节数：4 字节大端
     */
    public static final int LENGTH_FIELD_LENGTH = 4;

    /**
     * 长度字段是否包含自身：不包含（只描述报文体长度），故为 0
     */
    public static final int LENGTH_ADJUSTMENT = 0;

    /**
     * 解码后跳过的字节数：4（去掉长度头，交给字符串解码器的只有报文体）
     */
    public static final int INITIAL_BYTES_TO_STRIP = 4;

    // ==================================================================================
    // 两端必须一致的默认数值：服务端与客户端 SDK 按同一份「呼吸节奏」
    // ==================================================================================

    /**
     * TCP 接入默认端口（业务系统建连）
     */
    public static final int TCP_PORT = 9091;

    /**
     * 心跳间隔：写空闲这么久就主动发一次 PING（两端同值）
     *
     * <p>只要任一侧按这个节奏发声，连接就不会被对方判死；因此它是「下限」而不是「必须精确一致」——
     * 但两端取同一个值最省事：谁都没在推消息时，双方各自探活，谁先发现断链谁先处理。
     */
    public static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    /**
     * 服务端失联阈值：读空闲这么久没收到任何上行数据就关闭连接
     */
    public static final long SERVER_HEARTBEAT_TIMEOUT_MS = 90_000L;

    /**
     * 客户端失联阈值：读空闲这么久没收到任何下行数据就判定链路半开并重连
     *
     * <p>刻意比 {@link #SERVER_HEARTBEAT_TIMEOUT_MS} 小：客户端要抢在服务端关连接之前自己重连，
     * 否则就是「服务端已经把连接关了，客户端还在旧的 socket 上傻等」，中间这段时间推送全部丢失。
     * 两者不能反过来（下面有断言兜底）。
     */
    public static final long CLIENT_HEARTBEAT_TIMEOUT_MS = 30_000L;

    /**
     * 单帧报文体上限（字节），超出即断开
     *
     * <p>两端取同一个值：它既是对「异常长度字段」的防护（读爆内存），也是业务报文的实际天花板——
     * 客户端按 1MB 发、服务端按 64KB 收，结果是服务端直接断开，且只在推送大报文时复现。
     */
    public static final int MAX_FRAME_LENGTH = 65_536;

    static {
        if (HEARTBEAT_INTERVAL_MS <= 0) {
            throw new IllegalStateException("心跳间隔必须为正数");
        }
        if (CLIENT_HEARTBEAT_TIMEOUT_MS >= SERVER_HEARTBEAT_TIMEOUT_MS) {
            throw new IllegalStateException("客户端判死阈值必须早于服务端关闭阈值，否则链路断了客户端还在旧连接上等");
        }
        if (MAX_FRAME_LENGTH <= 0) {
            throw new IllegalStateException("单帧上限必须为正数");
        }
    }
}
