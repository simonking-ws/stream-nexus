package com.simonking.nexus.ws.client.tcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.TcpConstants;
import com.simonking.stream.nexus.common.enums.TcpEvent;
import com.simonking.stream.nexus.common.model.NexusMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端侧的报文处理器
 *
 * <p>它只做三件事：<b>记录回执、答应心跳、发现链路断了</b>。
 * 推送的发起在 {@link NexusTcpClient}——推送不等回执，写进连接就返回，
 * 因此这里收到 {@code RESULT} 只记日志，不做请求—回执配对（少了这层，「回执错配到别的请求上」这类问题也就不存在了）。
 *
 * <p>心跳必须自己做：TCP 是裸流，没有 WebSocket 那样的协议层 ping/pong 帧，
 * 只能靠 {@code IdleStateHandler} + 应用级 PING/PONG。
 * 这里与服务端是配套的：服务端写空闲 15s 发 PING、读空闲 90s 关连接；
 * 客户端写空闲 15s 发 PING、读空闲 30s 就判定链路半开并重连
 * ——客户端的阈值必须更小，否则「服务端已经关了，客户端还在旧连接上发消息」，消息全部落空。
 *
 * <p>本对象每条连接新建一个（非 {@code @Sharable}），因为它持有归属客户端的引用。
 *
 * @author simonking
 */
@Slf4j
@RequiredArgsConstructor
class TcpClientHandler extends SimpleChannelInboundHandler<String> {

    private final NexusTcpClient client;

    private final ObjectMapper objectMapper;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("[nexus-tcp] 已连接 {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String text) {
        NexusMessage<Object, TcpEvent> message;
        try {
            // 必须带出泛型实参：event 是泛型字段，按原始类型反序列化只会得到字符串，
            // 后面与 TcpEvent 常量比较会永远不成立（且编译器看不出来）
            message = objectMapper.readValue(text, new TypeReference<NexusMessage<Object, TcpEvent>>() {
            });
        } catch (Exception e) {
            // 解析不了的报文不关连接：多半是协议版本不一致，关了只会让重连线程反复空转
            log.warn("[nexus-tcp] 报文解析失败, text={}", text, e);
            return;
        }
        if (message == null || message.getEvent() == null) {
            log.warn("[nexus-tcp] 报文缺少 event, text={}", text);
            return;
        }

        switch (message.getEvent()) {
            case RESULT:
                // 只记日志：推送不等回执，没人等着用它
                log.debug("[nexus-tcp] 推送回执 {}", message.getData());
                break;
            case ERROR:
                // 参数类错误连接保持：改完报文还能接着推。同样只记日志——调用方早已返回
                log.warn("[nexus-tcp] 服务端返回错误 {}", message.getData());
                break;
            case PING:
                write(ctx, TcpEvent.PONG);
                break;
            case PONG:
                break;
            default:
                log.warn("[nexus-tcp] 忽略下行事件 {}", message.getEvent());
                break;
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleState state = ((IdleStateEvent) evt).state();
            if (state == IdleState.WRITER_IDLE) {
                // 写空闲：主动探活。有推送时写空闲会被重置，天然做到「有数据就不发心跳」
                write(ctx, TcpEvent.PING);
            } else if (state == IdleState.READER_IDLE) {
                // 读空闲：服务端静默了。半开连接（进程被杀 / NAT 丢表）只有这个能发现，
                // 关掉后由 channelInactive 触发重连
                log.warn("[nexus-tcp] 心跳超时（{}ms 无下行数据），主动断开并重连",
                        client.getHeartbeatTimeoutMs());
                ctx.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        client.onDisconnected();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("[nexus-tcp] 连接异常", cause);
        ctx.close();
    }

    /**
     * 下发一条控制类报文（PING / PONG）
     */
    private void write(ChannelHandlerContext ctx, TcpEvent event) {
        NexusMessage<Object, TcpEvent> message = NexusMessage.<Object, TcpEvent>builder()
                .event(event)
                .bizModule(TcpConstants.SYS_MODULE)
                .action(actionOf(event))
                .ts(System.currentTimeMillis())
                .build();
        try {
            ctx.writeAndFlush(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("[nexus-tcp] 下发 {} 失败", event, e);
            ctx.close();
        }
    }

    /**
     * 控制类报文的 action：与服务端 {@code TcpFrameHandler} 的映射保持一致
     */
    private String actionOf(TcpEvent event) {
        switch (event) {
            case PING:
                return NexusConstants.ACTION_PING;
            case PONG:
                return NexusConstants.ACTION_PONG;
            case RESULT:
                return TcpConstants.ACTION_RESULT;
            case ERROR:
                return TcpConstants.ACTION_ERROR;
            default:
                return TcpConstants.ACTION_PUSH;
        }
    }
}
