package com.simonking.nexus.websocket.server.handler;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.constant.WsConstants;
import com.simonking.nexus.websocket.enums.WsEvent;
import com.simonking.nexus.websocket.model.WsClient;
import com.simonking.nexus.websocket.model.WsMessage;
import com.simonking.nexus.websocket.registry.WsClientRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket 业务帧处理器
 *
 * <p>职责：握手完成后登记连接 -> 下发建连回执 -> 处理上行（心跳应答 / 业务上报）-> 心跳驱动回收。
 *
 * <p>心跳用 {@code IdleStateHandler} 而非定时任务：
 * 写空闲 = 该下发 PING 了，读空闲 = 客户端失联该关了。写操作会自动重置写空闲计时，
 * 天然做到「有推送就不发心跳」，比固定频率的定时任务更省流量。
 *
 * <p>注意：浏览器 {@code WebSocket} 对象收不到协议层的 ping/pong 帧，
 * 所以这里的 PING 是**应用级文本帧**，客户端必须自己在 onmessage 里识别并回 PONG。
 *
 * @author simonking
 */
@Slf4j
@RequiredArgsConstructor
public class WsFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final WsProperties properties;

    private final WsClientRegistry registry;

    private final JsonMapper jsonMapper;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete complete) {
            onHandshakeComplete(ctx, complete);
            return;
        }
        if (evt instanceof IdleStateEvent idle) {
            WsClient client = resolve(ctx);
            if (idle.state() == IdleState.WRITER_IDLE && client != null) {
                write(client, WsMessage.builder()
                        .event(WsEvent.PING)
                        .bizModule(WsConstants.SYS_MODULE)
                        .action(WsConstants.ACTION_PING)
                        .ts(System.currentTimeMillis())
                        .build());
            } else if (idle.state() == IdleState.READER_IDLE) {
                // 半开连接：TCP 已断但服务端不知道，只有「读空闲」能发现
                log.info("[ws] 心跳超时，回收连接, clientId={}", client == null ? "-" : client.getClientId());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        WsClient client = resolve(ctx);
        if (client == null) {
            ctx.close();
            return;
        }
        long now = System.currentTimeMillis();
        client.setLastActiveTime(now);
        client.getUplinkCount().incrementAndGet();

        WsMessage<?> message;
        try {
            message = jsonMapper.readValue(frame.text(), WsMessage.class);
        } catch (Exception e) {
            log.warn("[ws] 上行报文解析失败, clientId={}, text={}", client.getClientId(), frame.text(), e);
            return;
        }
        if (message == null || message.getEvent() == null) {
            return;
        }

        if (message.getEvent() == WsEvent.PONG) {
            // 心跳应答：唯一能证明「客户端真的活着」的证据
            client.setLastPongTime(now);
            return;
        }

        // 上行消息：本服务只做回显，便于测试页自证链路通；真实业务请在自己的系统里处理
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("clientId", client.getClientId());
        echo.put("bizModule", message.getBizModule());
        echo.put("action", message.getAction());
        echo.put("data", message.getData());
        write(client, WsMessage.builder()
                .event(WsEvent.MESSAGE)
                .bizModule(WsConstants.SYS_MODULE)
                .action(WsConstants.ACTION_ECHO)
                .ts(now)
                .data(echo)
                .build());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String clientId = ctx.channel().attr(WsConstants.ATTR_CLIENT_ID).get();
        if (clientId != null && registry.contains(clientId)) {
            log.info("[ws] 客户端断开连接, clientId={}", clientId);
            registry.remove(clientId);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[ws] 连接异常, clientId={}", ctx.channel().attr(WsConstants.ATTR_CLIENT_ID).get(), cause);
        ctx.close();
    }

    /**
     * 握手完成：登记连接，并把客户端ID随回执下发
     *
     * <p>客户端ID 在握手阶段就已确定（自带或服务端生成），这里只是把它回传回去：
     * 客户端拿它可以自证身份，业务系统拿它做定向推送。
     */
    private void onHandshakeComplete(ChannelHandlerContext ctx, WebSocketServerProtocolHandler.HandshakeComplete complete) {
        Set<String> modules = ctx.channel().attr(WsConstants.ATTR_MODULES).get();
        String ip = ctx.channel().attr(WsConstants.ATTR_IP).get();
        String clientId = ctx.channel().attr(WsConstants.ATTR_CLIENT_ID).get();
        if (modules == null || clientId == null) {
            ctx.close();
            return;
        }
        WsClient client = registry.register(clientId, ctx.channel(), modules, ip, complete.requestUri());
        if (client == null) {
            // 理论上不会发生：握手阶段已经做过冲突检查，这里只是并发下的兜底
            ctx.channel().writeAndFlush(
                    new CloseWebSocketFrame(WsConstants.CLOSE_CODE_DUPLICATE, "clientId already registered"));
            ctx.close();
            return;
        }
        log.info("[ws] 客户端建立连接, clientId={}, modules={}, ip={}", clientId, modules, ip);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clientId", clientId);
        data.put("modules", modules);
        data.put("heartbeatInterval", properties.getHeartbeatInterval().toMillis());
        write(client, WsMessage.builder()
                .event(WsEvent.CONNECTED)
                .bizModule(WsConstants.SYS_MODULE)
                .action(WsConstants.ACTION_CONNECTED)
                .ts(System.currentTimeMillis())
                .data(data)
                .build());
    }

    private WsClient resolve(ChannelHandlerContext ctx) {
        String clientId = ctx.channel().attr(WsConstants.ATTR_CLIENT_ID).get();
        return clientId == null ? null : registry.get(clientId);
    }

    private void write(WsClient client, WsMessage<?> message) {
        try {
            client.getChannel().writeAndFlush(new TextWebSocketFrame(jsonMapper.writeValueAsString(message)));
            client.getDownlinkCount().incrementAndGet();
        } catch (Exception e) {
            log.warn("[ws] 下发失败, clientId={}", client.getClientId(), e);
            registry.remove(client.getClientId());
        }
    }
}
