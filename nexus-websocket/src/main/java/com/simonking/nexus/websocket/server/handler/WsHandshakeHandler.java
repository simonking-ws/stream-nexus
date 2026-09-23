package com.simonking.nexus.websocket.server.handler;

import com.simonking.nexus.websocket.auth.PushAppRegistry;
import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.nexus.websocket.constant.WsChannelKeys;
import com.simonking.nexus.websocket.registry.WsClientRegistry;
import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.constant.WsConstants;
import com.simonking.stream.nexus.common.util.IpUtils;
import com.simonking.stream.nexus.common.util.NexusUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * WebSocket 握手前置校验
 *
 * <p>必须挂在 {@code WebSocketServerProtocolHandler} **之前**：此时报文还是 {@link FullHttpRequest}，
 * 可以在升级成 WebSocket 之前就把不合法的连接用 HTTP 状态码拒绝掉（401 / 400 / 409 / 503）。
 * 一旦握手完成再拒绝，就只能用关闭帧，浏览器侧拿不到可读的原因。
 *
 * <p>校验通过后不消费报文，{@code retain()} 一把再往后传，交给协议处理器完成升级；
 * 同时把客户端ID / 模块 / IP 写进 Channel 属性，供后续的帧处理器登记连接。
 *
 * <p><b>客户端ID 由服务端在这里生成</b>（UUID，见 {@link NexusUtils#newClientId()}）：
 * 终端只需带订阅模块，不用管自己的身份。
 *
 * @author simonking
 */
@Slf4j
@RequiredArgsConstructor
public class WsHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final WsProperties properties;

    private final WsClientRegistry registry;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        // 1. 建连鉴权：WebSocket 握手无法自定义请求头，令牌只能走查询参数
        if (properties.isConnectAuthEnabled()) {
            String expected = properties.getConnectAuthToken();
            String actual = firstParam(decoder, NexusConstants.PARAM_CONNECT_TOKEN);
            if (!StringUtils.hasText(expected) || actual == null
                    || !PushAppRegistry.constantTimeEquals(expected, actual)) {
                log.warn("[ws] 建连令牌校验失败, uri={}, remote={}", request.uri(), ctx.channel().remoteAddress());
                reject(ctx, HttpResponseStatus.UNAUTHORIZED, "unauthorized: invalid connect token");
                return;
            }
        }

        // 2. 连接数上限
        if (properties.getMaxConnections() > 0 && registry.size() >= properties.getMaxConnections()) {
            reject(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "connection limit reached");
            return;
        }

        // 3. 客户端ID：服务端生成，客户端无需传递（也无从伪造）
        ctx.channel().attr(WsChannelKeys.CLIENT_ID).set(NexusUtils.newClientId());

        Set<String> modules = WsClientRegistry.normalizeModules(decoder.parameters().get(WsConstants.PARAM_MODULES));
        ctx.channel().attr(WsChannelKeys.MODULES).set(modules);
        ctx.channel().attr(WsChannelKeys.IP).set(resolveIp(ctx, request));
        ctx.channel().attr(WsChannelKeys.URI).set(request.uri());

        // 不消费报文：交回给 WebSocketServerProtocolHandler 完成握手升级
        ctx.fireChannelRead(ReferenceCountUtil.retain(request));
    }

    private String firstParam(QueryStringDecoder decoder, String name) {
        List<String> values = decoder.parameters().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /**
     * 解析客户端 IP：优先信任代理头（Nginx 反代下 TCP 对端地址只会是网关地址），
     * 取不到再退回对端地址。仅供台账展示，不参与任何鉴权判定。
     *
     * <p>具体解析与归一化逻辑在 {@link IpUtils}，与 nexus-sse 共用同一套口径：
     * 同一个网关后面的两个服务，台账里展示的 IP 必须一致，否则排障时会对不上。
     */
    private String resolveIp(ChannelHandlerContext ctx, FullHttpRequest request) {
        return IpUtils.resolve(request.headers().get(NexusConstants.HEADER_X_FORWARDED_FOR),
                request.headers().get(NexusConstants.HEADER_X_REAL_IP),
                remoteIp(ctx));
    }

    /**
     * 没有代理头时退回 TCP 对端地址，只取 IP 不带端口
     */
    private String remoteIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        }
        return String.valueOf(ctx.channel().remoteAddress());
    }

    /**
     * 归一化展示用的 IP：IPv6 环回 → {@code 127.0.0.1}，IPv4 映射的 IPv6 → 点分十进制
     */
    private String normalizeIp(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (!StringUtils.hasText(v)) {
            return "-";
        }
        // 带方括号的 IPv6（[::1]:8080 这类带端口的写法）
        if (v.startsWith("[") && v.contains("]")) {
            v = v.substring(1, v.indexOf(']'));
        }
        // IPv4 映射的 IPv6：::ffff:1.2.3.4 → 1.2.3.4（末段是点分十进制，IPv6 分组不会出现点）
        int lastColon = v.lastIndexOf(':');
        if (lastColon >= 0 && v.substring(lastColon + 1).indexOf('.') > 0) {
            v = v.substring(lastColon + 1);
        }
        // IPv6 环回（::1 的完整写法是 0:0:0:0:0:0:0:1）→ 统一成 127.0.0.1
        if ("::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v)) {
            v = "127.0.0.1";
        }
        return v;
    }

    /**
     * 以 HTTP 状态码拒绝：写完立即关闭，不再往后传播升级请求
     */
    private void reject(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
