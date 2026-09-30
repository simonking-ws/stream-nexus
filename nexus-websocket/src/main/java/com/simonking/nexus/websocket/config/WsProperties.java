package com.simonking.nexus.websocket.config;

import com.simonking.stream.nexus.common.constant.TcpConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * WebSocket 服务配置
 *
 * <p>一个进程三组端口：
 * <ul>
 *     <li>{@code ws-port}：Netty WebSocket（9090），面向海量终端，只负责收推送；</li>
 *     <li>{@code tcp-port}：Netty TCP（9091），面向少量业务系统，长连接把消息交进来；</li>
 *     <li>{@code server.port}：HTTP（8089，由 Spring 配），REST 推送 + 管理界面。</li>
 * </ul>
 *
 * <p>安全策略也因此不同——WebSocket 面向公网终端，TCP 与 REST 一样只对内网开放
 * （TCP 通道不做应用鉴权，靠的是「端口不暴露」这一层）。
 *
 * <p>心跳与单帧上限的默认值取自 {@link TcpConstants}：客户端 SDK 用的是同一份常量，
 * 两端不容许各写各的（改了值而 SDK 没同步发布，线上就是一头新节奏一头旧节奏）。
 *
 * @author simonking
 */
@Data
@ConfigurationProperties(prefix = "nexus.ws")
public class WsProperties {

    /**
     * WebSocket 服务端口（终端建连）
     */
    private int wsPort = 9090;

    /**
     * TCP 服务端口（业务系统建连，长连接版推送通道）
     *
     * <p>除了端口，TCP 通道不单独配置任何参数：心跳节奏、单帧上限、连接上限、Netty 线程数
     * 全部沿用下面的 WebSocket 配置。两条链在同一个进程里跑同一套 Netty 模型，
     * 分开配只会让人以为它们可以不一样——而实际上能调的只有端口。
     */
    private int tcpPort = TcpConstants.TCP_PORT;

    /**
     * WebSocket 握手路径
     */
    private String wsPath = "/ws";

    /**
     * 心跳间隔：写空闲这么久就下发一次 PING（TCP 通道共用）
     */
    private Duration heartbeatInterval = Duration.ofMillis(TcpConstants.HEARTBEAT_INTERVAL_MS);

    /**
     * 心跳超时：读空闲这么久就判定失联并关闭（TCP 通道共用）
     *
     * <p>客户端 SDK 的判死阈值是 {@link TcpConstants#CLIENT_HEARTBEAT_TIMEOUT_MS}，
     * 比这里小——客户端要抢在被关之前自己重连。
     */
    private Duration heartbeatTimeout = Duration.ofMillis(TcpConstants.SERVER_HEARTBEAT_TIMEOUT_MS);

    /**
     * 最大 WebSocket 连接数，0 表示不限制
     */
    private int maxConnections = 30000;

    /**
     * 单个帧最大长度（字节），WebSocket 与 TCP 共用
     */
    private int maxFrameLength = TcpConstants.MAX_FRAME_LENGTH;

    /**
     * Netty boss 线程数
     */
    private int bossThreads = 1;

    /**
     * Netty worker 线程数，0 表示取 Netty 默认值（CPU 核数 * 2）
     */
    private int workerThreads = 0;

    /**
     * 推送鉴权开关（appId + apiKey）
     *
     * <p>注意：推送应用（appId / apiKey）不在此配置，而是运行时维护在
     * {@code PushAppRegistry}：内置默认应用 {@code test-demo / c3RyZWFtLW5leHVz}（白名单 {@code test}，只读），
     * 其余应用在 {@code /admin} 管理页「推送应用」页签增删，
     * 并落盘到 {@link #appStorePath} 对应的 JSON 文件（重启后仍在）
     */
    private boolean authEnabled = true;

    /**
     * 推送应用台账的持久化文件（JSON）
     *
     * <p>管理页新增 / 改 key / 删除都会同步写回这个文件，进程重启时由
     * {@code PushAppStore} 读回内存，避免「重启回到默认应用」。
     *
     * <p>相对路径以**模块根目录**（{@code nexus-websocket}）为基准，而不是进程工作目录：
     * 从仓库根启动、从模块目录启动、或 IDEA 里换个运行目录，台账都落在同一个
     * {@code nexus-websocket/data} 下，不会分裂成两份（配绝对路径则原样采用）。
     * 多实例部署请各自指向独立的本地文件（或改用共享存储实现 {@code PushAppStore}）——
     * 本实现是单实例的本地文件，多实例各自为政会互相覆盖认知。
     */
    private String appStorePath = "data/push-apps.json";

    /**
     * 建连鉴权开关（共享令牌）
     */
    private boolean connectAuthEnabled = false;

    /**
     * 建连令牌。开关打开但此处留空时，建连请求一律拒绝（失败关闭）
     */
    private String connectAuthToken = "";

    /**
     * 控制台页面拼接 WebSocket 地址时使用的对外地址（host 或 host:port）
     */
    private String publicEndpoint = "";

    /**
     * IP 归属地（城市）离线库位置：ip2region 的 xdb 数据文件
     *
     * <p>支持 {@code classpath:} 与 {@code file:} 前缀；相对路径的 file: 以进程工作目录为基准，
     * 部署时建议写成绝对路径，避免换个启动目录就找不到库。
     *
     * <p>文件缺失不是致命错误：台账城市列降级显示 {@code -}，其余功能照常。
     * 内网部署（客户端全是私有地址）本就不需要它——私有地址直接判定为「局域网」，不查库。
     */
    private String ip2regionPath = "classpath:ip2region.xdb";
}
