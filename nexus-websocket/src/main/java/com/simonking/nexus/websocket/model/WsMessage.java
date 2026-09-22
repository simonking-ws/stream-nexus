package com.simonking.nexus.websocket.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simonking.nexus.websocket.enums.WsEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 统一消息体（双向复用）
 *
 * <p>下行：服务端 --WebSocket--> 客户端，{@code event = CONNECTED / MESSAGE / PING / KICKED}
 * <br>
 * 上行：客户端 --WebSocket--> 服务端，{@code event = PONG}（心跳应答）或 {@code MESSAGE}（业务上报）
 *
 * <p>协议层字段（{@code event}）用枚举保证稳定；业务层字段（{@code bizModule} / {@code action}）用字符串，
 * 业务方自由扩展，无需修改本服务。
 *
 * <p>约定：
 * <ol>
 *     <li>{@code id} 单调递增（见 {@link com.simonking.stream.nexus.common.util.IdGenerator}），
 *         客户端据此丢弃乱序 / 重复消息；**心跳不带 id**，避免污染自增序列；</li>
 *     <li>{@code bizModule} 同时是路由键（连接订阅模块、推送按模块命中），粒度较粗，
 *         业务方必须在 {@code data} 中携带归属标识（如 itemId / orderId）供客户端二次区分；</li>
 *     <li>WebSocket 有帧级可靠性，但**不存在业务级确认**：心跳只证明连接存活，不代表消息已被处理。</li>
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
public class WsMessage<T> {

    /**
     * 消息ID：全局唯一且单调递增。心跳消息不设置
     */
    private String id;

    /**
     * 事件类型
     */
    private WsEvent event;

    /**
     * 业务模块：既是业务标识也是路由键，如 lot / order
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
