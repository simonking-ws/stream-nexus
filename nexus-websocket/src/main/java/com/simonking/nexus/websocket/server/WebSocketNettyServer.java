package com.simonking.nexus.websocket.server;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.registry.WsClientRegistry;
import com.simonking.nexus.websocket.server.handler.WsFrameHandler;
import com.simonking.nexus.websocket.server.handler.WsHandshakeHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket 服务端（终端建连通道）
 *
 * <p>端口 {@code nexus.ws.ws-port}（默认 9090），只负责维持长连接与下发消息。
 * 业务系统永远不需要接触这个端口，它们走 HTTP 端口的 {@code POST /ws/push} 推消息即可。
 *
 * <p>流水线顺序不能随意调整：
 * <pre>
 *   HttpServerCodec -> HttpObjectAggregator -> ChunkedWriteHandler
 *     -> WsHandshakeHandler      （HTTP 层就拒绝非法连接，必须在协议处理器之前）
 *     -> WebSocketServerProtocolHandler（升级 + 帧编解码 + 关闭帧处理）
 *     -> IdleStateHandler        （心跳：写空闲发 PING，读空闲关连接）
 *     -> WsFrameHandler          （业务）
 * </pre>
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketNettyServer {

    private final WsProperties properties;

    private final WsClientRegistry registry;

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
                        WebSocketServerProtocolConfig config = WebSocketServerProtocolConfig.newBuilder()
                                .websocketPath(properties.getWsPath())
                                // 为true可以匹配/ws?token=abc&room=123，否不能建立连接
                                .checkStartsWith(true)
                                .maxFramePayloadLength(properties.getMaxFrameLength())
                                .handshakeTimeoutMillis(10_000L)
                                .build();

                        channel.pipeline().addLast(new HttpServerCodec());
                        channel.pipeline().addLast(new HttpObjectAggregator(65536));
                        channel.pipeline().addLast(new ChunkedWriteHandler());
                        channel.pipeline().addLast(new WsHandshakeHandler(properties, registry));
                        channel.pipeline().addLast(new WebSocketServerProtocolHandler(config));
                        channel.pipeline().addLast(new IdleStateHandler(readerIdle, writerIdle, 0, TimeUnit.SECONDS));
                        channel.pipeline().addLast(new WsFrameHandler(properties, registry, jsonMapper));
                    }
                });

        ChannelFuture future = bootstrap.bind(properties.getWsPort()).sync();
        serverChannel = future.channel();
        log.info("WebSocket 服务启动完成, 监听 {}, 握手路径 {}", serverChannel.localAddress(), properties.getWsPath());
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
