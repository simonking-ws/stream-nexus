package com.simonking.nexus.ws.client.model;

/**
 * 推送结果
 *
 * @param messageId 服务端生成的消息ID
 * @param total     命中的连接数
 * @param success   提交成功的连接数
 * @param failed    提交失败（已回收）的连接数
 * @author simonking
 */
public record PushResult(String messageId, int total, int success, int failed) {

    @Override
    public String toString() {
        return "PushResult{messageId=" + messageId + ", total=" + total
                + ", success=" + success + ", failed=" + failed + "}";
    }
}
