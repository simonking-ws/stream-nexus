package com.simonking.stream.nexus.common.enums;

/**
 * TCP 通道消息事件类型（业务系统 --TCP--> 推送服务）
 *
 * <p>与 {@link WsEvent} / {@link SseEvent} 面向的<b>对象不同</b>：后两者是「服务端 <-> 终端」的业务通道，
 * 这里是「业务系统 <-> 推送服务」的接入通道——业务系统用 TCP 长连接把待推送的消息交给服务，
 * 由服务扇出到终端。它不承担终端业务消息，因此事件集合完全独立，也无需与另两个对齐。
 *
 * <ul>
 *     <li>{@code PUSH}：上行，推送请求（{@code data} 为 {@code PushRequest}）；</li>
 *     <li>{@code RESULT}：下行，本次推送结果（{@code data} 为 {@code PushResult}）；</li>
 *     <li>{@code PING} / {@code PONG}：双向心跳，TCP 是裸流，必须自己维持活性判断；</li>
 *     <li>{@code ERROR}：下行，报文不可解析或业务校验不通过（附带原因，连接保持）。</li>
 * </ul>
 *
 * <p><b>这里没有鉴权事件</b>：TCP 与 REST 推送一样，假定只对内网开放（端口不暴露即边界），
 * 能连上就视为可信业务系统——它推的消息最终仍要按终端的订阅模块扇出，
 * 拿不到任何「越权」能力，再叠一层 appId / apiKey 只是增加一套要维护的凭据。
 *
 * @author simonking
 */
public enum TcpEvent {

    /**
     * 上行：推送请求
     */
    PUSH,

    /**
     * 下行：推送结果
     */
    RESULT,

    /**
     * 双向：心跳探测
     */
    PING,

    /**
     * 双向：心跳应答
     */
    PONG,

    /**
     * 下行：报文错误 / 业务校验不通过
     */
    ERROR
}
