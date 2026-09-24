package com.simonking.stream.nexus.sse.location;

import com.simonking.stream.nexus.sse.config.SseProperties;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.LongByteArray;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 归属地（城市）查询：给连接台账补一列「从哪来」
 *
 * <p>用 ip2region 离线库（xdb）而非在线 API：本服务是内网推送通道，运维页不能反过来依赖公网，
 * 在线接口一断就整列空白，还有频率限制。离线库一次性加载进内存，查询是纯内存二分，微秒级。
 *
 * <p><b>数据文件缺失不是致命错误</b>：{@code ip2region.xdb} 需要自备（仓库不收 11MB 二进制），
 * 缺库时 {@link #city(String)} 统一返回 {@code -}，台账其余功能照常。
 * 内网部署其实也用不上它——私有地址段直接判定为「局域网」，压根不进库查询。
 *
 * <p>结果走内存缓存：同一个出口 IP 往往挂着几十上百条连接，没必要反复查。
 * 缓存无上限但有天然上界——IP 种类远少于连接数。
 *
 * @author simonking
 */
@Slf4j
@Component
public class IpLocationService implements DisposableBean {

    /**
     * 私有地址（含本机环回）的归属：内网部署下绝大多数连接都是它
     */
    private static final String LAN = "局域网";

    /**
     * 查不出来时的占位：与台账其它列的「-」保持一致，不引入第三种空值写法
     */
    private static final String UNKNOWN = "-";

    /**
     * 为空表示未启用（数据文件缺失或加载失败）
     */
    private final Searcher searcher;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public IpLocationService(SseProperties properties, ResourceLoader loader) {
        Searcher loaded = null;
        String path = properties.getIp2regionPath();
        try {
            Resource res = loader.getResource(path);
            if (res.exists()) {
                // 3.x 起库内容用 LongByteArray 承载，且建 Searcher 必须显式给 IP 版本。
                // 版本从库头自动识别（IPv4 / IPv6 两种 xdb 都能用），省掉一个「我这是几版库」的配置项
                LongByteArray content;
                try (InputStream in = res.getInputStream()) {
                    content = Searcher.loadContentFromInputStream(in);
                }
                Version version = Version.fromHeader(Searcher.loadHeaderFromBuffer(content));
                // 整库进内存：查询全程无 IO，是三种缓存策略里最适合服务端常驻的
                loaded = Searcher.newWithBuffer(version, content);
                log.info("[sse] IP 库已加载：{}（IP 版本 {}），台账城市列启用", path, version.name);
            } else {
                log.warn("[sse] 未找到 IP 库 {}，台账城市列将显示 -："
                        + "下载 ip2region.xdb 放 resources，或用 nexus.sse.ip2region-path 指定路径", path);
            }
        } catch (Exception e) {
            // 加载失败按「没这个库」处理：城市列是可有可无的展示项，不该拖垮启动
            log.warn("[sse] IP 库加载失败 {}，台账城市列将显示 -：{}", path, e.getMessage());
        }
        this.searcher = loaded;
    }

    /**
     * 查询 IP 归属地
     *
     * @param ip 归一化后的 IP（见 {@code IpUtils#normalize}），可为 null / {@code -}
     * @return 城市描述（如「广东省深圳市」）、私有地址返回「局域网」、查不到返回 {@code -}
     */
    public String city(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (v.isEmpty() || UNKNOWN.equals(v)) {
            return UNKNOWN;
        }
        if (isPrivate(v)) {
            return LAN;
        }
        if (searcher == null) {
            return UNKNOWN;
        }
        return cache.computeIfAbsent(v, this::doSearch);
    }

    private String doSearch(String ip) {
        // Searcher 在 buffer 模式下不持有可变状态，仍加锁：官方未承诺并发安全，
        // 而这里是运维页低频调用（一次拉全量连接），锁的代价可以忽略
        synchronized (searcher) {
            try {
                return format(searcher.search(ip));
            } catch (Exception e) {
                return UNKNOWN;
            }
        }
    }

    /**
     * 归一化 ip2region 的原始结果：{@code 国家|区域|省份|城市|运营商}，例 {@code 中国|0|广东省|深圳市|电信}
     *
     * <p>只取省份 + 城市（下标 2、3）：国家太粗、运营商太细，进台账都属噪音。
     * 缺失位填 {@code 0}，需跳过；省份与城市相同时（直辖市）去重成一个。
     */
    private String format(String region) {
        if (region == null || region.isBlank()) {
            return UNKNOWN;
        }
        String[] parts = region.split("\\|");
        Set<String> picked = new LinkedHashSet<>();
        for (int i = 2; i <= 3 && i < parts.length; i++) {
            String v = parts[i].trim();
            if (!v.isEmpty() && !"0".equals(v)) {
                picked.add(v);
            }
        }
        if (picked.isEmpty()) {
            // 只有国家位有值（境外 IP 常见）：退回国家，总比空着强
            String country = parts.length > 0 ? parts[0].trim() : "";
            return country.isEmpty() || "0".equals(country) ? UNKNOWN : country;
        }
        return String.join(" ", picked);
    }

    /**
     * 私有 / 保留地址判定：这些地址没有归属地，查库也是浪费
     *
     * <p>解析不了的写法（非点分十进制、含端口等）一律按私有处理——宁可显示「局域网」，
     * 也不要把脏数据喂给查询库换来一条误导性的城市。
     */
    private static boolean isPrivate(String ip) {
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        if (ip.indexOf(':') >= 0) {
            String v = ip.toLowerCase();
            return v.startsWith("fc") || v.startsWith("fd") || v.startsWith("fe80");
        }
        String[] seg = ip.split("\\.");
        if (seg.length != 4) {
            return true;
        }
        try {
            int a = Integer.parseInt(seg[0]);
            int b = Integer.parseInt(seg[1]);
            if (a == 10 || a == 127) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 169 && b == 254) {
                return true;
            }
            // 运营商级 NAT（100.64/10）：同样是内网，不会出现在公网路由表里
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    @Override
    public void destroy() {
        if (searcher != null) {
            try {
                searcher.close();
            } catch (Exception ignored) {
                // 进程在退出，关闭失败无意义
            }
        }
    }
}
