package com.simonking.stream.nexus.common.enums;

/**
 * 消息事件类型（协议层）
 *
 * <p>枚举只描述消息在协议中的角色，业务语义一律由 {@code bizModule} + {@code action} 自由表达。
 *
 * <p>只有心跳这一对事件占用枚举：{@link #PING} / {@link #PONG} 是唯一需要客户端显式响应的协议交互。
 * 永不过期策略下「客户端主动说话」是服务端判断连接存活的**唯一依据**（半开连接下 {@code send()} 依然返回成功，
 * 服务端单向探测无效），而心跳模式把它固化成一次「一问一答」，不再依赖业务消息。
 *
 * <p>其余协议事件仍交给 SSE / EventSource 原生机制承载：
 * <ul>
 *     <li>建连感知：{@code es.onopen}</li>
 *     <li>断开重连：{@code es.onerror} + 浏览器自动重连</li>
 *     <li>服务端回收：{@code SseEmitter#complete()}</li>
 * </ul>
 *
 * @author simonking
 */
public enum EventEnum {

    /**
     * 业务消息：服务端 --SSE--> 客户端
     */
    MESSAGE,

    /**
     * 心跳探测：服务端 --SSE--> 客户端。
     *
     * <p>**不带 id**（见设计文档难点 2）：SSE 规定浏览器会把最后收到的 {@code id:} 通过
     * {@code Last-Event-ID} 回传，若心跳带 id，断线重连时上报的会是心跳 id 而非业务消息 id，
     * 补发语义被污染。
     */
    PING,

    /**
     * 心跳应答：客户端 --HTTP--> 服务端。
     *
     * <p>客户端收到 {@link #PING} 后应立即上报（无需节流），服务端据此刷新连接的存活时间。
     */
    PONG
}
