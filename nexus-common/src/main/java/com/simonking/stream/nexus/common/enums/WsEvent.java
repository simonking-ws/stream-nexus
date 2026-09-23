package com.simonking.stream.nexus.common.enums;

/**
 * WebSocket 消息事件类型
 *
 * <p>WebSocket 是全双工的，同一个消息体既用于下行也用于上行，靠 {@code event} 区分语义：
 * <ul>
 *     <li>{@code CONNECTED}：下行，握手成功回执，携带客户端ID / 订阅模块 / 心跳节奏；</li>
 *     <li>{@code MESSAGE}：双向，业务消息（下行 = 推送，上行 = 客户端上报）；</li>
 *     <li>{@code PING}：下行，服务端心跳探测；</li>
 *     <li>{@code PONG}：上行，客户端心跳应答；</li>
 *     <li>{@code KICKED}：下行，管理员强制下线通知（紧接着服务端发关闭帧）。</li>
 * </ul>
 *
 * <p>浏览器原生 API 无法收发协议层的 ping/pong 帧（{@code WebSocket} 对象根本不暴露），
 * 所以心跳必须走应用级消息，这也是 {@code PING} / {@code PONG} 存在的唯一理由。
 *
 * <p>与 SSE 的 {@link SseEvent} 是两套事件：SSE 走 {@code event:} 行（协议字段），
 * WebSocket 走消息体里的 {@code event} 字段，两者取值不必对齐，只是都由服务端下发。
 *
 * @author simonking
 */
public enum WsEvent {

    /**
     * 建连成功回执
     */
    CONNECTED,

    /**
     * 业务消息
     */
    MESSAGE,

    /**
     * 心跳探测（服务端 -> 客户端）
     */
    PING,

    /**
     * 心跳应答（客户端 -> 服务端）
     */
    PONG,

    /**
     * 强制下线通知
     */
    KICKED
}
