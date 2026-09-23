package com.simonking.nexus.websocket.server;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.core.TcpPushService;
import com.simonking.nexus.websocket.registry.TcpClientRegistry;
import com.simonking.nexus.websocket.server.handler.TcpFrameHandler;
import com.simonking.stream.nexus.common.constant.TcpConstants;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * TCP 接入服务端（业务系统建连通道）
 *
 * <p>端口 {@code nexus.ws.tcp-port}（默认 9091），与 WebSocket 服务（9090）同进程部署：
 * 业务系统用 TCP 长连接把消息交进来，本服务直接查终端注册表扇出到 WebSocket 通道，
 * 中间不经过任何转发，也就没有额外的一跳延迟和故障点。
 *
 * <p>为什么还要有这一条链：REST 推送（{@code POST /ws/push}）是短连接，
 * 高频推送时每条消息都要重建 HTTP 连接；业务系统侧引入客户端 SDK 后，
 * 一条长连接可以复用连接、批量推、还能靠心跳提前发现链路中断。
 *
 * <p>配置全部沿用 {@link WsProperties}：两条链在同一个进程里跑同一套 Netty 模型，
 * 心跳节奏、单帧上限、连接上限、线程数共用一份即可，只有端口必须分开。
 *
 * <p>流水线顺序不能随意调整：
 * <pre>
 *   LengthFieldBasedFrameDecoder   （入站：按 4 字节长度头切帧，解决粘包/拆包）
 *   -> LengthFieldPrepender        （出站：给响应加同样的长度头）
 *   -> StringDecoder / StringEncoder（与报文编码对齐，UTF-8）
 *   -> IdleStateHandler            （心跳：写空闲发 PING，读空闲关连接）
 *   -> TcpFrameHandler             （业务：收推送请求 + 回执）
 * </pre>
 * 出站处理器必须排在字符串编码器<b>之前</b>（更靠近 head）：出站事件从 tail 往 head 走，
 * 先由 StringEncoder 把字符串编成字节，再由 LengthFieldPrepender 补长度头，顺序反了
 * 对端就会把 JSON 的前 4 个字节当成长度，解出一个荒谬的长度值然后断开。
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TcpNettyServer {

    private final WsProperties properties;

    private final TcpPushService pushService;

    private final TcpClientRegistry registry;

    private final JsonMapper jsonMapper;

    private EventLoopGroup bossGroup;

    private EventLoopGroup workerGroup;

    private Channel serverChannel;

    /**
     * 启动并阻塞监听（请在独立线程中调用）
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(Math.max(1, properties.getBossThreads()));
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        long readerIdle = Math.max(properties.getHeartbeatTimeout().toSeconds(), 0);
                        long writerIdle = Math.max(properties.getHeartbeatInterval().toSeconds(), 0);

                        channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                properties.getMaxFrameLength(),
                                TcpConstants.LENGTH_FIELD_OFFSET,
                                TcpConstants.LENGTH_FIELD_LENGTH,
                                TcpConstants.LENGTH_ADJUSTMENT,
                                TcpConstants.INITIAL_BYTES_TO_STRIP));
                        channel.pipeline().addLast(new LengthFieldPrepender(TcpConstants.LENGTH_FIELD_LENGTH));
                        channel.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                        channel.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                        channel.pipeline().addLast(new IdleStateHandler(readerIdle, writerIdle, 0, TimeUnit.SECONDS));
                        channel.pipeline().addLast(new TcpFrameHandler(properties, pushService, registry, jsonMapper));
                    }
                });

        ChannelFuture future = bootstrap.bind(properties.getTcpPort()).sync();
        serverChannel = future.channel();
        log.info("TCP 服务启动完成, 监听 {}", serverChannel.localAddress());
        serverChannel.closeFuture().sync();
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (Exception ignored) {
            // 忽略
        }
        shutdownGracefully();
    }

    private void shutdownGracefully() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 3, TimeUnit.SECONDS);
        }
    }
}
