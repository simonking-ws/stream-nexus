package com.simonking.nexus.websocket.server.handler;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.core.TcpPushService;
import com.simonking.nexus.websocket.model.TcpClient;
import com.simonking.nexus.websocket.registry.TcpClientRegistry;
import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.TcpConstants;
import com.simonking.stream.nexus.common.enums.TcpEvent;
import com.simonking.stream.nexus.common.model.NexusMessage;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import com.simonking.stream.nexus.common.util.IpUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * TCP 接入通道的报文处理器（业务系统 --TCP--> 本服务）
 *
 * <p>它只做两件事：<b>收推送请求、回执</b>。真正的扇出交给 {@code WsPusher}——
 * 本服务与 WebSocket 服务在同一个进程里，共享同一张终端连接注册表，
 * 因此 TCP 收到消息后可以直接写终端的 Channel，不需要任何跨进程转发。
 * 这正是「把 WebSocket 服务独立部署、业务系统只引入客户端 SDK」能成立的前提。
 *
 * <p><b>不做应用鉴权</b>：连接建立即可推送。TCP 端口与 REST 推送接口一样只对内网开放，
 * 「端口不暴露」就是它的边界；业务系统推的消息仍要按终端的订阅模块扇出，拿不到额外能力。
 * 少一套凭据，就少一套要分发、轮换、排查的密钥。
 *
 * <p>心跳必须自己做：TCP 是裸流，没有 WebSocket 那样的协议层 ping/pong 帧，
 * 也没有 SSE 那样的「下一次发送即探测」，只能靠 {@code IdleStateHandler} + 应用级 PING/PONG
 * （读空闲超时关闭，才能发现进程被杀、NAT 静默丢表这类半开连接）。
 *
 * @author simonking
 */
@Slf4j
@RequiredArgsConstructor
public class TcpFrameHandler extends SimpleChannelInboundHandler<String> {

    private final WsProperties properties;

    private final TcpPushService pushService;

    private final TcpClientRegistry registry;

    private final JsonMapper jsonMapper;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int max = properties.getMaxConnections();
        if (max > 0 && registry.size() >= max) {
            log.warn("[tcp] 连接数已达上限 {}, 拒绝 remote={}", max, ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        registry.register(ctx.channel(), IpUtils.resolve(null, null, remoteIp(ctx)));
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String text) {
        long now = System.currentTimeMillis();
        TcpClient client = registry.get(channelId(ctx));
        if (client != null) {
            client.setLastActiveTime(now);
            client.getMsgCount().incrementAndGet();
        }

        NexusMessage<Object, TcpEvent> message;
        try {
            // 必须带出泛型实参：event 是泛型字段，按原始类型反序列化只会得到字符串，
            // 后面与 TcpEvent 常量比较会永远不成立（且编译器看不出来）
            message = jsonMapper.readValue(text, new TypeReference<NexusMessage<Object, TcpEvent>>() {});
        } catch (Exception e) {
            log.warn("[tcp] 报文解析失败, remote={}, text={}", ctx.channel().remoteAddress(), text, e);
            write(ctx, TcpEvent.ERROR, "invalid payload: " + e.getMessage());
            return;
        }
        if (message == null || message.getEvent() == null) {
            write(ctx, TcpEvent.ERROR, "event is required");
            return;
        }

        TcpEvent event = message.getEvent();
        switch (event) {
            case PUSH -> handlePush(ctx, message);
            // 客户端也可以主动探活，服务端只回一个 PONG，不做任何状态判断
            case PING -> write(ctx, TcpEvent.PONG, null);
            case PONG -> {
                if (client != null) {
                    client.setLastPongTime(now);
                }
            }
            default -> write(ctx, TcpEvent.ERROR, "unsupported event: " + event);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.WRITER_IDLE) {
                write(ctx, TcpEvent.PING, null);
            } else if (idle.state() == IdleState.READER_IDLE) {
                // 半开连接：TCP 已断但服务端不知道，只有「读空闲」能发现
                log.info("[tcp] 心跳超时，回收连接 channelId={}", channelId(ctx));
                ctx.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        registry.remove(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[tcp] 连接异常, channelId={}", channelId(ctx), cause);
        ctx.close();
    }

    /**
     * 推送：校验不通过只回 ERROR 不关连接——客户端改完报文可以接着推
     */
    private void handlePush(ChannelHandlerContext ctx, NexusMessage<Object, TcpEvent> message) {
        PushRequest request;
        try {
            request = jsonMapper.convertValue(message.getData(), PushRequest.class);
        } catch (Exception e) {
            write(ctx, TcpEvent.ERROR, "invalid push request: " + e.getMessage());
            return;
        }
        if (request == null) {
            write(ctx, TcpEvent.ERROR, "data is required");
            return;
        }
        try {
            PushResult result = pushService.push(request);
            write(ctx, TcpEvent.RESULT, result);
        } catch (IllegalArgumentException e) {
            write(ctx, TcpEvent.ERROR, e.getMessage());
        }
    }

    /**
     * 下发一条控制类报文；写入失败即关闭连接（对端多半已经不在了）
     */
    private void write(ChannelHandlerContext ctx, TcpEvent event, Object data) {
        NexusMessage<Object, TcpEvent> message = NexusMessage.<Object, TcpEvent>builder()
                .event(event)
                .bizModule(TcpConstants.SYS_MODULE)
                .action(actionOf(event))
                .ts(System.currentTimeMillis())
                .data(data)
                .build();
        try {
            ctx.writeAndFlush(jsonMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("[tcp] 下发失败, remote={}", ctx.channel().remoteAddress(), e);
            ctx.close();
        }
    }

    private String actionOf(TcpEvent event) {
        return switch (event) {
            case PUSH -> TcpConstants.ACTION_PUSH;
            case RESULT -> TcpConstants.ACTION_RESULT;
            case PING -> NexusConstants.ACTION_PING;
            case PONG -> NexusConstants.ACTION_PONG;
            case ERROR -> TcpConstants.ACTION_ERROR;
        };
    }

    private String channelId(ChannelHandlerContext ctx) {
        return ctx.channel().id().asShortText();
    }

    /**
     * 没有代理头可看，直接取 TCP 对端地址（业务系统是内网直连，对端即真实来源）
     */
    private String remoteIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        }
        return String.valueOf(ctx.channel().remoteAddress());
    }
}
