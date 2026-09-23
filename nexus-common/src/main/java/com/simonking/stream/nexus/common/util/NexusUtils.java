package com.simonking.stream.nexus.common.util;

import com.simonking.stream.nexus.common.constant.NexusConstants;

import java.util.UUID;

/**
 * SSE / WebSocket 共用的寻址工具
 *
 * <p>两个服务的「客户端ID + 业务模块」语义完全一致（客户端ID 服务端生成、随连接生命周期变化；
 * 全局模块 {@code *} 双向生效），因此生成与判定口径必须收在一处：
 * 否则业务系统维护的「用户 → clientId」映射在两个服务间无法互换，
 * 订阅侧归一化与推送侧路由也会因为大小写/写法差异而悄悄错位。
 *
 * @author simonking
 */
public final class NexusUtils {

    private NexusUtils() {
    }

    /**
     * 生成一个客户端ID：UUID v4（36 字符，形如 {@code 6f1d2a3c-...}）
     *
     * <p>由<b>服务端</b>在建连阶段生成，客户端不需要（也不允许）自带：
     * 让客户端自带ID 意味着客户端可以声明任意身份，服务端要么承担被冒用的风险，
     * 要么再叠一层令牌校验；交给服务端生成则没有这些问题。
     *
     * <p>UUID 足够长（122 位随机），海量终端并发建连的碰撞概率也可以忽略——
     * 不像通道ID / 32 位哈希那种短标识，几万条连接就会撞。
     *
     * <p>代价是它<b>随连接生命周期变化</b>（重连即换）：要按用户维度稳定寻址，请用模块订阅
     * 或在业务系统侧维护「用户 → 当前 clientId」的映射（客户端建连后上报）。
     */
    public static String newClientId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 判断是否全局模块：{@code *} / {@code Global} 等写法都算，忽略大小写与首尾空白
     *
     * <p>订阅侧靠它与倒排索引（大小写敏感）对齐，推送侧靠它认出 {@code Global} 这类写法。
     *
     * @param module 模块名，允许为 null
     */
    public static boolean isGlobal(String module) {
        return module != null && NexusConstants.GLOBAL_MODULE.equalsIgnoreCase(module.trim());
    }
}
