package com.simonking.stream.nexus.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推送结果
 *
 * @author simonking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushResult {

    /**
     * 本次生成的消息ID
     */
    private String messageId;

    /**
     * 命中的连接数
     */
    private int total;

    /**
     * 发送成功的连接数
     */
    private int success;

    /**
     * 发送失败（已回收）的连接数
     */
    private int failed;
}
