package com.simonking.nexus.ws.client.exception;

/**
 * 推送失败异常
 *
 * <p>客户端只有这一种异常，而且只在「报文还没发出去」时抛：参数为空、连不上推送服务、
 * 报文不可序列化。业务方要么记日志丢弃（推送多为幂等广播），要么自行重试
 * ——客户端不做重试，因为它不知道业务语义是否幂等。
 *
 * <p>已经写进连接的报文不再回抛：推送不等回执，调用方早就返回了，写入结果也不监听，
 * 异常没人接；服务端 {@code ERROR} 只在日志里记一笔。
 *
 * @author simonking
 */
public class PushException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PushException(String message) {
        super(message);
    }

    public PushException(String message, Throwable cause) {
        super(message, cause);
    }
}
