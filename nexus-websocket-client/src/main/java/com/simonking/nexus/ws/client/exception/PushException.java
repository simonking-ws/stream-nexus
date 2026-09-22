package com.simonking.nexus.ws.client.exception;

/**
 * 推送客户端异常：连接失败、注册失败、推送超时、推送被拒
 *
 * @author simonking
 */
public class PushException extends RuntimeException {

    public PushException(String message) {
        super(message);
    }

    public PushException(String message, Throwable cause) {
        super(message, cause);
    }
}
