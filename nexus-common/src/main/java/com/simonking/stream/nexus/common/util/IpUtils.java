package com.simonking.stream.nexus.common.util;

import org.springframework.util.StringUtils;

/**
 * 客户端 IP 解析工具
 *
 * <p>SSE（Tomcat / {@code HttpServletRequest}）与 WebSocket（Netty / {@code FullHttpRequest}）
 * 拿请求头的方式不同，但解析口径必须一致：同一个网关后面的两个服务，台账里展示的 IP 必须对得上，
 * 否则排障时会一边是真实客户端、一边是网关地址。
 *
 * <p>解析结果<b>仅供台账展示</b>，不参与任何鉴权判定：代理头可伪造，
 * 只有 TCP 对端地址是可信的，而那又可能是网关地址。
 *
 * @author simonking
 */
public final class IpUtils {

    /**
     * 解析不出来时的占位符：宁可显示成 {@code -}，也不要把 null 塞进台账
     */
    private static final String UNKNOWN = "-";

    private IpUtils() {
    }

    /**
     * 解析客户端 IP：优先信任代理头（反代下 TCP 对端地址只会是网关地址），取不到再退回对端地址
     *
     * @param forwardedFor  {@code X-Forwarded-For} 头（可为 null）
     * @param realIp        {@code X-Real-IP} 头（可为 null）
     * @param remoteAddress TCP 对端地址（可为 null）
     * @return 归一化后的 IP，取不到时为 {@code -}
     */
    public static String resolve(String forwardedFor, String realIp, String remoteAddress) {
        String ip = firstInChain(forwardedFor);
        if (!StringUtils.hasText(ip)) {
            ip = firstInChain(realIp);
        }
        if (!StringUtils.hasText(ip)) {
            ip = remoteAddress;
        }
        return normalize(ip);
    }

    /**
     * 取代理链中的第一个有效地址：链形如 {@code "client, proxy1, proxy2"}，最左才是真实客户端；
     * 部分网关拿不到时会填 {@code unknown}，直接跳过继续往右找
     *
     * @param header 原始头值（可为 null）
     * @return 第一个有效地址，取不到时为 null
     */
    public static String firstInChain(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        for (String part : header.split(",")) {
            String v = part.trim();
            if (StringUtils.hasText(v) && !"unknown".equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 归一化展示用的 IP：IPv6 环回 → {@code 127.0.0.1}，IPv4 映射的 IPv6 → 点分十进制
     *
     * <p>本机（localhost）访问在开了 IPv6 的机器上，容器给的是 {@code 0:0:0:0:0:0:0:1}
     * 而不是 127.0.0.1，直接进台账既认不出来也没法按 IP 过滤；
     * 双栈环境下常见的 {@code ::ffff:1.2.3.4} 一并归一成点分十进制。
     *
     * @param ip 原始地址（可为 null）
     * @return 归一化后的地址，空值时为 {@code -}
     */
    public static String normalize(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (!StringUtils.hasText(v)) {
            return UNKNOWN;
        }
        // 带方括号的 IPv6（[::1]:8080 这类带端口的写法）
        if (v.startsWith("[") && v.contains("]")) {
            v = v.substring(1, v.indexOf(']'));
        }
        // IPv4 映射的 IPv6：::ffff:1.2.3.4 → 1.2.3.4（末段是点分十进制，IPv6 分组不会出现点）
        int lastColon = v.lastIndexOf(':');
        if (lastColon >= 0 && v.substring(lastColon + 1).indexOf('.') > 0) {
            v = v.substring(lastColon + 1);
        }
        // IPv6 环回（::1 的完整写法是 0:0:0:0:0:0:0:1）→ 统一成 127.0.0.1
        if ("::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v)) {
            v = "127.0.0.1";
        }
        return v;
    }
}
