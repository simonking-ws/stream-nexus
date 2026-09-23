package com.simonking.stream.nexus.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 外部推送入参
 *
 * <p>支持两种寻址方式，可单独使用，也可同时使用（命中并集、按 clientId 去重）：
 * <ol>
 *     <li>按业务模块广播：{@code bizModule}，推送给所有订阅了该模块的连接；</li>
 *     <li>按客户端定向：{@code clientIds}，只推送给指定的连接。</li>
 * </ol>
 *
 * <p>{@code bizModule} 同时是消息体字段与路由键，推送侧与订阅侧取值必须完全一致（含大小写），
 * 否则会静默推空。
 *
 * <p>{@code bizModule} 缺省（null / 空白）时按全局模块 {@code *} 处理：广播给全部在线连接，
 * 与显式传 {@code *} 等价；但指定了 {@code clientIds} 时保持纯定向，不扩散到无关连接。
 *
 * @author simonking
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushRequest {

    /**
     * 目标业务模块，如 lot / order。
     *
     * <p>留空按全局模块 {@code *} 处理（广播全部在线连接）；带 {@code clientIds} 时留空则为纯定向
     */
    private String bizModule;

    /**
     * 定向推送的客户端ID列表
     *
     * <p>ID 由<b>服务端</b>建连时分配、随建连回执下发给客户端，重连即换；
     * 业务系统需自行维护它与用户的映射（客户端每次建连后上报刷新）。
     */
    private List<String> clientIds;

    /**
     * 业务动作，如 BID / RESULT / CREATE
     */
    private String action;

    /**
     * 业务数据。多订阅场景下必须携带归属标识（如 itemId）
     */
    private Object data;
}
