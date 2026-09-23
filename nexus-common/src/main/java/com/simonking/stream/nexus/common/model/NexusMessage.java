package com.simonking.stream.nexus.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE / WebSocket 统一消息体（双向复用，两个服务共用）
 *
 * <p>两个协议的字段结构完全一致，只有 {@code event} 的取值集合不同
 * （SSE 用 {@link com.simonking.stream.nexus.common.enums.SseEvent}，
 * WebSocket 用 {@link com.simonking.stream.nexus.common.enums.WsEvent}），
 * 因此事件类型做成泛型参数 {@code E}：一份消息体定义覆盖两个协议，
 * 又不会出现「拿 SSE 的事件去填 WS 消息」这类编译期本可拦住的错。
 *
 * <p>协议层字段（{@code event}）用枚举保证稳定；业务层字段（{@code bizModule} / {@code action}）
 * 用字符串，业务方自由扩展，无需修改公共包。
 *
 * <p>约定（两个服务同口径）：
 * <ol>
 *     <li>{@code id} 必须单调递增（见 {@link com.simonking.stream.nexus.common.util.IdGenerator}），
 *         客户端据此丢弃乱序 / 重复消息；SSE 还用它做 {@code Last-Event-ID} 断线续传；
 *         **心跳不带 id**，否则会污染续传语义（见设计文档难点 2）；</li>
 *     <li>{@code bizModule} 同时是路由键（连接订阅模块、推送按模块命中），粒度较粗，
 *         业务方必须在 {@code data} 中携带归属标识（如 itemId / orderId）供客户端二次区分；</li>
 *     <li>客户端收到 {@code ts} 小于已处理值的消息应直接丢弃（防乱序）；</li>
 *     <li>**不存在消息级确认**：心跳只证明连接存活，不代表某条消息已送达或被处理
 *         （WebSocket 只有帧级可靠性，SSE 同理）。</li>
 * </ol>
 *
 * @param <T> 业务数据类型
 * @param <E> 事件类型：SseEvent / WsEvent
 * @author simonking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NexusMessage<T, E> {

    /**
     * 消息ID：全局唯一且单调递增。对应 SSE 的 {@code id} 字段。心跳消息不设置
     */
    private String id;

    /**
     * 事件类型：SSE 为 MESSAGE / PING / PONG，WebSocket 为 CONNECTED / MESSAGE / PING / PONG / KICKED
     */
    private E event;

    /**
     * 业务模块：既是业务标识也是路由键（连接订阅模块、推送按模块命中），如 lot / order
     */
    private String bizModule;

    /**
     * 业务动作：业务方自定义，如 CREATE / UPDATE / BID
     */
    private String action;

    /**
     * 服务端时间戳（毫秒）。客户端据此丢弃乱序消息
     */
    private Long ts;

    /**
     * 具体业务数据
     */
    private T data;
}
