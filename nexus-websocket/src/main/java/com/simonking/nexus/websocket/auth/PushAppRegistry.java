package com.simonking.nexus.websocket.auth;

import com.simonking.nexus.websocket.config.WsProperties;
import com.simonking.stream.nexus.common.constant.NexusConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 推送应用注册表：鉴权的唯一数据源
 *
 * <p>应用不在配置文件里维护：内置一个开箱即用的默认应用
 * {@value NexusConstants#DEFAULT_APP_ID} / {@value NexusConstants#DEFAULT_API_KEY}（白名单 {@code test}），
 * 它是**只读凭证**：始终在列表里，appId / apiKey / 白名单 都不可改、也不可删除
 * （改了等于把联调与文档的基准改掉，而且所有接入方都要跟着换）。
 * 其余应用在 {@code /admin} 管理页运行时增删。
 *
 * <p>增删由 {@link PushAppStore} 落盘到本地 JSON 文件，**重启后台账仍在**：
 * 首次启动（还没有存储文件）播种默认应用并写盘；之后每次启动读回文件。
 * 换个 {@code PushAppStore} 实现（DB / Redis）即可适配多实例，对推送鉴权无感——它只读这里的内存表。
 *
 * <p>校验顺序不可颠倒：必须**先按 appId 定位应用**（确定归属白名单），再常量时间比对密钥。
 * 反过来做就变成了「遍历所有应用找一个密钥匹配的」，既慢又会泄漏信息。
 *
 * @author simonking
 */
@Slf4j
@Component
public class PushAppRegistry {

    /**
     * 内置默认应用的模块白名单：只放 {@code test}，避免它成为「能推任意模块的万能钥匙」
     *
     * <p>appId / apiKey 是与 SSE 服务共用的内置常量，收在 {@link NexusConstants}，不在这里重复定义。
     */
    public static final List<String> DEFAULT_ALLOWED_MODULES = List.of("test");

    private final boolean authEnabled;

    private final PushAppStore store;

    /**
     * 按 appId 有序，管理界面展示时天然稳定排序
     */
    private final ConcurrentMap<String, PushApp> apps = new ConcurrentSkipListMap<>();

    public PushAppRegistry(WsProperties properties, PushAppStore store) {
        this.authEnabled = properties.isAuthEnabled();
        this.store = store;
        boolean hasStore = store.exists();
        if (hasStore) {
            // 文件存在就采信里面的内容；内置默认应用随后兜底补回
            store.load().forEach(app -> apps.put(app.appId(), app));
        }
        boolean dirty = false;

        // 内置默认应用始终在、且三个字段恒定：首次启动 / 老台账里没有 / 被删过 / 被手改过，
        // 都纠正回内置值并落盘。它是本地联调与推送测试页的保底项
        PushApp builtIn = apps.get(NexusConstants.DEFAULT_APP_ID);
        if (builtIn == null) {
            apps.put(NexusConstants.DEFAULT_APP_ID, defaultApp());
            dirty = true;
        } else if (!NexusConstants.DEFAULT_API_KEY.equals(builtIn.apiKey())
                || !DEFAULT_ALLOWED_MODULES.equals(builtIn.allowedModules())) {
            // 台账被人手改过：内置凭证只读，一律纠正（页面与接口本来也不许改）
            apps.put(NexusConstants.DEFAULT_APP_ID, defaultApp());
            dirty = true;
        }
        if (dirty || !hasStore) {
            persist();
        }
        log.info("推送应用载入完成：共 {} 个{}台账 {}，鉴权开关 authEnabled={}", apps.size(),
                dirty ? "（内置默认应用 " + NexusConstants.DEFAULT_APP_ID + " 已校正），" : "，", store.path(), authEnabled);
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
            return List.of(NexusConstants.MODULE_WILDCARD);
        }
        List<String> normalized = modules.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of(NexusConstants.MODULE_WILDCARD) : normalized;
    }

    /**
     * 内置默认应用：始终存在于台账，三个字段都不可改、整条不可删除（接口与页面都会拦，这里再兜一层）
     */
    public static boolean isBuiltIn(String appId) {
        return NexusConstants.DEFAULT_APP_ID.equals(appId == null ? null : appId.trim());
    }

    /**
     * 内置默认应用的固定形态（启动时校正、页面展示都以此为准）
     */
    public static PushApp defaultApp() {
        return new PushApp(NexusConstants.DEFAULT_APP_ID, NexusConstants.DEFAULT_API_KEY, DEFAULT_ALLOWED_MODULES);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    /**
     * 台账落盘位置（管理页展示用：让人知道改的东西写在哪，重启会不会丢）
     */
    public String storePath() {
        return store.path().toString();
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
        if (allowed.contains(NexusConstants.MODULE_WILDCARD)) {
            return true;
        }
        return allowed.stream().anyMatch(m -> m.equals(bizModule));
    }

    /**
     * 新增或覆盖同名应用；先落盘再进内存的顺序反过来会「内存有、磁盘没有」，
     * 落盘失败直接抛：宁可这一次改不动，也不要让人以为存住了
     */
    public void save(String appId, String apiKey, List<String> allowedModules) {
        PushApp app = new PushApp(appId, apiKey, normalize(allowedModules));
        synchronized (apps) {
            Map<String, PushApp> snapshot = new LinkedHashMap<>(apps);
            snapshot.put(app.appId(), app);
            store.save(List.copyOf(snapshot.values()));
            apps.put(app.appId(), app);
        }
    }

    /**
     * 删除应用：内置默认应用删不掉（始终在），其余删除后同步落盘
     */
    public boolean remove(String appId) {
        if (!StringUtils.hasText(appId)) {
            return false;
        }
        String key = appId.trim();
        if (isBuiltIn(key)) {
            log.warn("内置默认应用 {} 不可删除，已忽略该请求", key);
            return false;
        }
        synchronized (apps) {
            if (apps.remove(key) == null) {
                return false;
            }
            store.save(List.copyOf(apps.values()));
            return true;
        }
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", apps.size());
        result.put("authEnabled", authEnabled);
        result.put("defaultAppId", NexusConstants.DEFAULT_APP_ID);
        // 台账落盘位置：页面直接展示，让人知道改动写在哪、重启还在不在
        result.put("storePath", storePath());
        return result;
    }

    /**
     * 把当前内存台账写回文件（供启动时播种默认应用后落盘）
     */
    private void persist() {
        store.save(List.copyOf(apps.values()));
    }
}
