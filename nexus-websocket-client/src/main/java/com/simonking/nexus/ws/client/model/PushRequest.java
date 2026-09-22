package com.simonking.nexus.ws.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 推送请求
 *
 * <p>两种寻址方式可单独使用，也可以同时使用（命中并集、按 clientId 去重）：
 * <ol>
 *     <li>按业务模块广播：{@code bizModule}，命中所有订阅了该模块的 WebSocket 连接；</li>
 *     <li>按客户端定向：{@code clientIds}，只命中指定的连接。</li>
 * </ol>
 *
 * <p>客户端ID 由<b>服务端</b>在终端建连时生成（UUID），随建连回执下发给终端，
 * 终端再上报给业务系统。它<b>重连即换</b>：要按用户维度稳定寻址，优先用模块广播，
 * 或由业务系统在终端每次建连后刷新「用户 → clientId」映射。
 *
 * @param bizModule 目标业务模块，与 clientIds 至少填一个
 * @param clientIds 定向的客户端ID（终端自带，由业务系统自行维护与用户的映射）
 * @param action    业务动作，如 CREATE / BID
 * @param data      业务数据，任意可 JSON 序列化的对象
 * @author simonking
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PushRequest(String bizModule, List<String> clientIds, String action, Object data) {

    /**
     * 按模块广播
     */
    public static PushRequest of(String bizModule, String action, Object data) {
        return new PushRequest(bizModule, null, action, data);
    }

    /**
     * 定向推送
     */
    public static PushRequest to(List<String> clientIds, String action, Object data) {
        return new PushRequest(null, clientIds, action, data);
    }

    /**
     * 定向推送单个连接
     */
    public static PushRequest to(String clientId, String action, Object data) {
        return new PushRequest(null, List.of(clientId), action, data);
    }
}
