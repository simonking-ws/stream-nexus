package com.simonking.stream.nexus.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simonking.stream.nexus.common.enums.EventEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 统一消息体（双向复用）
 *
 * <p>下行：服务端 --SSE--> 客户端，{@code event = MESSAGE}（业务消息）或 {@code PING}（心跳探测）
 * <br>
 * 上行：客户端 --HTTP--> 服务端，{@code event = PONG}（心跳应答）
 *
 * <p>协议层字段（{@code event}）用枚举，保证稳定；
 * 业务层字段（{@code bizModule} / {@code action}）用字符串，业务方自由扩展，无需修改公共包。
 *
 * <p>约定：
 * <ol>
 *     <li>{@code id} 必须单调递增（见 {@link com.simonking.stream.nexus.common.util.IdGenerator}），
 *         用于 {@code Last-Event-ID} 断线续传与客户端乱序丢弃；
 *         **心跳不带 id**，否则会污染续传语义（见设计文档难点 2）；</li>
 *     <li>{@code bizModule} 同时是路由键（连接订阅模块、推送按模块命中），
 *         且粒度较粗，因此业务方必须在 {@code data} 中携带归属标识（如 itemId / orderId）
 *         供客户端做二次区分；</li>
 *     <li>客户端收到 {@code ts} 小于已处理值的消息应直接丢弃（防乱序）；</li>
 *     <li>**不存在消息级确认**：心跳只证明连接存活，不代表某条消息已送达或被处理。</li>
 * </ol>
 *
 * @param <T> 业务数据类型
 * @author simonking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SseMessage<T> {

    /**
     * 消息ID：全局唯一且单调递增。对应 SSE 的 {@code id} 字段。心跳消息不设置
     */
    private String id;

    /**
     * 事件类型：MESSAGE（下行业务消息） / PING（下行心跳） / PONG（上行心跳应答）
     */
    private EventEnum event;

    /**
     * 业务模块：既是业务标识也是路由键（连接订阅模块、推送按模块命中），如 lot / order
     */
    private String bizModule;

    /**
     * 业务动作：业务方自定义，如 CREATE / UPDATE / 001 / 002
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
