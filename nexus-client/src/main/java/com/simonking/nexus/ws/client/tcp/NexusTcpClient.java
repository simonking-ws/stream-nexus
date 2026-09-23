package com.simonking.nexus.ws.client.tcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonking.nexus.ws.client.exception.PushException;
import com.simonking.stream.nexus.common.constant.TcpConstants;
import com.simonking.stream.nexus.common.enums.TcpEvent;
import com.simonking.stream.nexus.common.model.NexusMessage;
import com.simonking.stream.nexus.common.model.PushRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * nexus-websocket 的 TCP 长连接推送客户端
 *
 * <p>业务系统引入后，一条长连接就能把消息推到浏览器，<b>不需要写任何 Netty / WebSocket 服务端代码</b>：
 * 本客户端把消息交给独立部署的推送服务（TCP 9091），由它查终端注册表扇出到 WebSocket 通道。
 *
 * <pre>
 *   业务系统 --TCP 长连接(本项目)--> nexus-websocket --WebSocket--> 浏览器
 * </pre>
 *
 * <p>参数用 Lombok {@code builder} 拼，之后直接 {@code push()}——整个类只有这一个推送入口，
 * 寻址方式（模块广播 / 终端定向）写在 {@link PushRequest} 里：
 * <pre>
 *     NexusTcpClient client = NexusTcpClient.builder().host("10.0.0.8").build();
 *     client.push(PushRequest.builder().bizModule("order").action("CREATE").data(data).build());
 *     client.close();                          // 应用退出时释放
 * </pre>
 *
 * <p><b>协议定义全部取自 {@code nexus-common}</b>：帧格式、默认数值（{@link TcpConstants}）、
 * 事件枚举（{@link TcpEvent}）、报文体（{@link NexusMessage}）、推送入参与回执（{@link PushRequest} / {@link PushResult}）
 * ——服务端用的是同一份，协议漂移的风险直接归零。正因为要复用这些<b>运行期类型</b>，
 * {@code nexus-common} 才按 Java 8 出包（见其 pom）：SDK 也是 Java 8 字节码，
 * 若 common 是 Java 17 字节码，老系统一加载就是 {@code UnsupportedClassVersionError}。
 * 额外的好处是 {@code nexus-common} 的 spring 依赖是 optional 的，SDK 不会把 Spring 带给业务系统。
 *
 * <p><b>不做应用鉴权</b>：TCP 端口只对内网开放，连上即可推，「端口不暴露」就是它的边界。
 *
 * <p><b>推送不等回执</b>：{@code push()} 把报文写进连接就返回，不等服务端处理完——
 * 推送本质是单向通知，业务线程不该为「终端收没收到」阻塞（收没收到也不影响业务库里的状态）。
 * 服务端照旧下发 {@code RESULT}，这里只在日志里记一下，不再做请求—回执配对。
 *
 * <p><b>单 EventLoop 线程模型</b>，因此重连不能在 EventLoop 线程里做——
 * {@code connect().sync()} 会把 EventLoop 自己等死；
 * 断线后由独立的 {@code nexus-tcp-reconnect} 线程按间隔重试。
 *
 * @author simonking
 */
@Slf4j
@Builder
public class NexusTcpClient implements AutoCloseable {

    private static final String DEFAULT_HOST = "127.0.0.1";

    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 3_000L;

    private static final long DEFAULT_RECONNECT_INTERVAL_MS = 3_000L;

    // ==================================================================================
    // 构造参数：全部有默认值，日常只用得到 host / port
    // ==================================================================================

    /** 推送服务地址 */
    @Builder.Default
    private String host = DEFAULT_HOST;

    /** 推送服务 TCP 端口（{@code nexus.ws.tcp-port}），不是 HTTP 的 8089 */
    @Builder.Default
    private int port = TcpConstants.TCP_PORT;

    /** 建连超时（毫秒） */
    @Builder.Default
    private long connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;

    /**
     * 心跳间隔（毫秒）：写空闲这么久就主动发一次 PING。0 = 不主动探活（仍应答服务端 PING）
     *
     * <p>与服务端 {@code nexus.ws.heartbeat-interval} 配套：客户端按这个节奏发声，
     * 服务端就永远不会因读空闲而关连接。
     */
    @Builder.Default
    private long heartbeatIntervalMs = TcpConstants.HEARTBEAT_INTERVAL_MS;

    /**
     * 失效判定（毫秒）：这么久没收到任何下行数据即判定链路半开并重连
     *
     * <p>必须小于服务端的 {@code nexus.ws.heartbeat-timeout}（默认 90s），
     * 否则「服务端已关、客户端还在旧连接上等回执」——这个大小关系由
     * {@link TcpConstants} 的静态断言兜底。
     */
    @Builder.Default
    private long heartbeatTimeoutMs = TcpConstants.CLIENT_HEARTBEAT_TIMEOUT_MS;

    /** 断线自动重连 */
    @Builder.Default
    private boolean autoReconnect = true;

    /** 重连重试间隔（毫秒） */
    @Builder.Default
    private long reconnectIntervalMs = DEFAULT_RECONNECT_INTERVAL_MS;

    /** 单帧报文体上限（字节），与服务端 {@code nexus.ws.max-frame-length} 同值 */
    @Builder.Default
    private int maxFrameLength = TcpConstants.MAX_FRAME_LENGTH;

    // ==================================================================================
    // 运行期状态：都是 final + 就地初始化，@Builder 不会把它们塞进 builder
    // ==================================================================================

    private final ObjectMapper objectMapper = newObjectMapper();

    private final AtomicReference<Channel> channelRef = new AtomicReference<Channel>();

    private final AtomicReference<EventLoopGroup> groupRef = new AtomicReference<EventLoopGroup>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /** 重连任务是否已在跑：断线会重复触发，只认第一个 */
    private final AtomicBoolean reconnecting = new AtomicBoolean();

    private final ScheduledThreadPoolExecutor reconnectExecutor = newExecutor();

    // ==================================================================================
    // 推送 API
    // ==================================================================================

    /**
     * 推送一条消息：<b>发后不管</b>，报文写进连接即返回，不等服务端回执
     *
     * <p>寻址方式写在 {@link PushRequest} 里，两种可单独用也可同时用（命中并集）：
     * <ul>
     *     <li>{@code bizModule}：按业务模块广播（<b>首选</b>），终端按业务身份订阅，与连接无关，重连后依然可达；</li>
     *     <li>{@code clientIds}：定向到指定终端，ID 由服务端建连时分配、<b>重连即换</b>。</li>
     * </ul>
     * 两者都为空时无从路由，直接抛 {@link PushException}。
     *
     * <p>并发调用是安全的：{@code Channel} 的 {@code writeAndFlush} 本身线程安全，
     * 各线程写进来的报文按调用顺序排队到 EventLoop 上发送。
     *
     * @param request 推送入参：模块 / 终端ID / 动作 / 业务数据
     * @throws PushException 参数不合法 / 连不上推送服务 / 报文序列化失败
     */
    public void push(PushRequest request) {
        if (request == null || (!hasText(request.getBizModule()) && isEmpty(request.getClientIds()))) {
            throw new PushException("bizModule 或 clientIds 必须有一个");
        }

        Channel channel = activeChannel();

        NexusMessage<Object, TcpEvent> frame = NexusMessage.<Object, TcpEvent>builder()
                .event(TcpEvent.PUSH)
                .bizModule(request.getBizModule())
                .action(request.getAction())
                .ts(System.currentTimeMillis())
                .data(request.getData())
                .build();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new PushException("报文序列化失败: " + e.getMessage(), e);
        }

        // 不挂 listener：推送不等回执，写失败也没有处理空间——调用方早已返回，
        // 异常没人接、也没法补发（业务语义是否幂等只有业务自己知道）；
        // 链路断了由 IdleStateHandler 读空闲判定 + channelInactive 触发重连，与这一帧无关
        channel.writeAndFlush(payload);
    }

    // ==================================================================================
    // 连接生命周期
    // ==================================================================================

    /**
     * 建立连接（可选）
     *
     * <p>不调也行——首次推送会惰性建连。主动调一次可以让「服务没起 / 地址填错」在启动阶段暴露，
     * 而不是等到第一次推送。
     *
     * @throws PushException 连接失败或超时；失败后仍会由重连线程自动重试
     */
    public synchronized void connect() {
        if (closed.get()) {
            throw new PushException("客户端已关闭，不能重复使用");
        }
        Channel current = channelRef.get();
        if (current != null && current.isActive()) {
            return;
        }
        EventLoopGroup group = groupRef.get();
        if (group == null || group.isShutdown()) {
            group = new NioEventLoopGroup(1, threadFactory("nexus-tcp-eventloop"));
            groupRef.set(group);
        }

        ChannelFuture future = newBootstrap(group).connect(host, port);
        try {
            if (!future.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new PushException("连接超时: " + host + ":" + port + " (" + connectTimeoutMs + "ms)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("连接被中断: " + host + ":" + port);
        }
        if (!future.isSuccess()) {
            throw new PushException("连接失败: " + host + ":" + port + " -> " + reason(future.cause()),
                    future.cause());
        }
        channelRef.set(future.channel());
        log.info("[nexus-tcp] 连接建立 {} -> {}:{}", future.channel().localAddress(), host, port);
    }

    /**
     * 释放：断连接、停心跳与重连线程
     *
     * <p>实现 {@link AutoCloseable}，可以直接 try-with-resources。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Channel channel = channelRef.getAndSet(null);
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
                // 关闭中的异常没有处理空间
            }
        }
        reconnectExecutor.shutdownNow();
        EventLoopGroup group = groupRef.getAndSet(null);
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        log.info("[nexus-tcp] 客户端已关闭");
    }

    // ==================================================================================
    // 流水线：与服务端同一套编解码器，顺序也不能改
    // ==================================================================================

    private Bootstrap newBootstrap(EventLoopGroup group) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.max(connectTimeoutMs, 1L))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        ChannelPipeline pipeline = channel.pipeline();
                        // 入站：按 4 字节长度头切帧，去掉粘包/拆包（常量取自 TcpConstants）
                        pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                maxFrameLength,
                                TcpConstants.LENGTH_FIELD_OFFSET,
                                TcpConstants.LENGTH_FIELD_LENGTH,
                                TcpConstants.LENGTH_ADJUSTMENT,
                                TcpConstants.INITIAL_BYTES_TO_STRIP));
                        // 出站：补同样的长度头。必须排在 StringEncoder 之前（更靠 head）——
                        // 出站事件从 tail 往 head 走，先编码成字节再补头，反了对端会把 JSON 前 4 字节当长度
                        pipeline.addLast(new LengthFieldPrepender(TcpConstants.LENGTH_FIELD_LENGTH));
                        pipeline.addLast(new StringDecoder(StandardCharsets.UTF_8));
                        pipeline.addLast(new StringEncoder(StandardCharsets.UTF_8));
                        // 心跳：读空闲判死重连，写空闲主动发 PING（0 表示不启用）
                        pipeline.addLast(new IdleStateHandler(
                                Math.max(heartbeatTimeoutMs, 0L),
                                Math.max(heartbeatIntervalMs, 0L),
                                0,
                                TimeUnit.MILLISECONDS));
                        pipeline.addLast(new TcpClientHandler(NexusTcpClient.this, objectMapper));
                    }
                });
        return bootstrap;
    }

    // ==================================================================================
    // 收发
    // ==================================================================================

    /**
     * 取一条可用连接：没有就惰性建连（失败抛 {@link PushException}，不积压消息）
     */
    private Channel activeChannel() {
        Channel channel = channelRef.get();
        if (channel != null && channel.isActive()) {
            return channel;
        }
        connect();
        channel = channelRef.get();
        if (channel == null || !channel.isActive()) {
            throw new PushException("未连接到推送服务 " + host + ":" + port);
        }
        return channel;
    }

    // ==================================================================================
    // 连接事件：由 TcpClientHandler 在 EventLoop 线程回调
    // ==================================================================================

    void onDisconnected() {
        channelRef.set(null);
        scheduleReconnect();
    }

    long getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    // ==================================================================================
    // 重连：独立线程，绝不在 EventLoop 线程里做
    // ==================================================================================

    private void scheduleReconnect() {
        if (!autoReconnect || closed.get()) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        final long interval = Math.max(reconnectIntervalMs, 1L);
        log.info("[nexus-tcp] 连接断开，{}ms 后开始重连 {}:{}", interval, host, port);

        final AtomicReference<ScheduledFuture<?>> self = new AtomicReference<ScheduledFuture<?>>();
        self.set(reconnectExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    cancelQuietly(self.get());
                    reconnecting.set(false);
                    return;
                }
                try {
                    connect();
                    log.info("[nexus-tcp] 重连成功 {}:{}", host, port);
                    cancelQuietly(self.get());
                    reconnecting.set(false);
                } catch (PushException e) {
                    log.warn("[nexus-tcp] 重连失败：{}，{}ms 后重试", e.getMessage(), interval);
                } catch (RuntimeException e) {
                    log.warn("[nexus-tcp] 重连异常，{}ms 后重试", interval, e);
                }
            }
        }, interval, interval, TimeUnit.MILLISECONDS));
    }

    private static void cancelQuietly(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    private static ObjectMapper newObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static ScheduledThreadPoolExecutor newExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory("nexus-tcp-reconnect"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadFactory threadFactory(final String name) {
        return new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static String reason(Throwable cause) {
        return cause == null ? "未知原因" : String.valueOf(cause.getMessage());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

}
