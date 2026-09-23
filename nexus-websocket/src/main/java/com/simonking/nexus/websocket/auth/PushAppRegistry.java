package com.simonking.nexus.websocket.auth;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.stream.nexus.common.constant.WsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 推送应用注册表：鉴权的唯一数据源
 *
 * <p>默认应用写死在代码里（{@code test / test_secret}），其余应用在管理界面运行时增删，
 * **仅存内存，重启回到默认应用**。这是刻意的取舍：本服务的定位是内网推送通道，
 * 应用数量个位数，引入持久化会带来配置漂移（改了库还要重启才生效才有意义）。
 *
 * <p>校验顺序不可颠倒：必须**先按 appId 定位应用**（确定归属白名单），再常量时间比对密钥。
 * 反过来做就变成了「遍历所有应用找一个密钥匹配的」，既慢又会泄漏信息。
 *
 * @author simonking
 */
@Slf4j
@Component
public class PushAppRegistry {

    public static final String DEFAULT_APP_ID = "test";

    public static final String DEFAULT_API_KEY = "test_secret";

    private final boolean authEnabled;

    /**
     * 按 appId 有序，管理界面展示时天然稳定排序
     */
    private final ConcurrentMap<String, PushApp> apps = new ConcurrentSkipListMap<>();

    public PushAppRegistry(WsProperties properties) {
        this.authEnabled = properties.isAuthEnabled();
        apps.put(DEFAULT_APP_ID, new PushApp(DEFAULT_APP_ID, DEFAULT_API_KEY, List.of(WsConstants.MODULE_WILDCARD)));
        log.info("推送应用载入完成，默认应用 {}/{}，鉴权开关 authEnabled={}", DEFAULT_APP_ID, DEFAULT_API_KEY, authEnabled);
    }

    /**
     * 生成随机密钥：UUID(16字节) → Base64 URL-safe 无补位 = 22 字符，放进请求头不必转义
     */
    public static String generateApiKey() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 模块白名单归一化：空 / 全空白一律视为 {@code *}（不限模块）
     */
    public static List<String> normalize(List<String> modules) {
        if (CollectionUtils.isEmpty(modules)) {
            return List.of(WsConstants.MODULE_WILDCARD);
        }
        List<String> normalized = modules.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of(WsConstants.MODULE_WILDCARD) : normalized;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public Collection<PushApp> list() {
        return apps.values();
    }

    public Optional<PushApp> find(String appId) {
        return Optional.ofNullable(apps.get(appId));
    }

    public boolean contains(String appId) {
        return apps.containsKey(appId);
    }

    /**
     * 凭证校验：鉴权关闭时一律放行（此时白名单为 null，调用方不做模块越权校验）
     *
     * @return 校验通过返回应用，否则 null
     */
    public PushApp authenticate(String appId, String apiKey) {
        if (!authEnabled) {
            return null;
        }
        PushApp app = apps.get(appId);
        if (app == null || !constantTimeEquals(app.apiKey(), apiKey)) {
            return null;
        }
        return app;
    }

    /**
     * 模块白名单校验：{@code allowed} 为 null（鉴权关闭）或含 {@code *} 时放行
     */
    public static boolean moduleAllowed(List<String> allowed, String bizModule) {
        if (allowed == null || !StringUtils.hasText(bizModule)) {
            return true;
        }
        if (allowed.contains(WsConstants.MODULE_WILDCARD)) {
            return true;
        }
        return allowed.stream().anyMatch(m -> m.equals(bizModule));
    }

    public void save(String appId, String apiKey, List<String> allowedModules) {
        apps.put(appId, new PushApp(appId, apiKey, normalize(allowedModules)));
    }

    public void remove(String appId) {
        apps.remove(appId);
    }

    /**
     * 常量时间比较，避免时序侧信道
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 概览信息，供管理界面展示
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", apps.size());
        result.put("authEnabled", authEnabled);
        result.put("defaultAppId", DEFAULT_APP_ID);
        return result;
    }
}
