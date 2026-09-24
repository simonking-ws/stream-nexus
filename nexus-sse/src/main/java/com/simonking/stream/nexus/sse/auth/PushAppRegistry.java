package com.simonking.stream.nexus.sse.auth;

import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.sse.config.SseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.util.Base64;
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
 * {@value #DEFAULT_APP_ID} / {@value #DEFAULT_API_KEY}（白名单 {@code test}），
 * 它是**只读凭证**：始终在列表里，appId / apiKey / 白名单 都不可改、也不可删除
 * （改了等于把联调与文档的基准改掉，而且所有接入方都要跟着换）。
 * 其余应用在 {@code /admin} 管理页运行时增删。
 *
 * <p>增删由 {@link PushAppStore} 落盘到本地 JSON 文件，**重启后台账仍在**：
 * 首次启动（还没有存储文件）播种默认应用并写盘；之后每次启动读回文件。
 * 换个 {@code PushAppStore} 实现（DB / Redis）即可适配多实例，
 * 对 {@link PushAuthInterceptor} 无感——它只读这里的内存表。
 *
 * @author simonking
 */
@Component
public class PushAppRegistry {

    private static final Logger log = LoggerFactory.getLogger(PushAppRegistry.class);

    /**
     * 内置默认应用（只读）：本地联调开箱即用，推送测试页下拉的保底选项
     */
    public static final String DEFAULT_APP_ID = "test-demo";

    public static final String DEFAULT_API_KEY = "c3RyZWFtLW5leHVz";

    /**
     * 内置默认应用的模块白名单：只放 {@code test}，避免它成为「能推任意模块的万能钥匙」
     */
    public static final List<String> DEFAULT_ALLOWED_MODULES = List.of("test");

    /**
     * 旧版内置应用：升级后由 {@link #DEFAULT_APP_ID} 取代，启动时若还是旧值就摘掉，
     * 否则台账里会躺着一个「不知道哪来的、还能推」的凭证
     */
    private static final String LEGACY_DEFAULT_APP_ID = "test";

    private static final String LEGACY_DEFAULT_API_KEY = "test_secret";

    private final boolean authEnabled;

    private final PushAppStore store;

    /**
     * 按 appId 有序：管理页列表顺序稳定，不随并发写入抖动。
     * 读写比极高（每次推送都读一次），用并发容器避免读锁竞争
     */
    private final ConcurrentMap<String, PushApp> apps = new ConcurrentSkipListMap<>();

    public PushAppRegistry(SseProperties properties, PushAppStore store) {
        this.authEnabled = properties.isAuthEnabled();
        this.store = store;
        boolean hasStore = store.exists();
        if (hasStore) {
            // 文件存在就采信里面的内容；内置默认应用随后兜底补回
            store.load().forEach(app -> apps.put(app.appId(), app));
        }
        boolean dirty = false;

        // 迁移：旧版内置应用（test / test_secret）在新内置应用就位后已无存在意义
        PushApp legacy = apps.get(LEGACY_DEFAULT_APP_ID);
        if (legacy != null && LEGACY_DEFAULT_API_KEY.equals(legacy.apiKey())) {
            apps.remove(LEGACY_DEFAULT_APP_ID);
            dirty = true;
            log.warn("检测到旧版内置应用 {}/{}，已移除（新版内置应用为 {}/{}）",
                    LEGACY_DEFAULT_APP_ID, LEGACY_DEFAULT_API_KEY, DEFAULT_APP_ID, DEFAULT_API_KEY);
        }

        // 内置默认应用始终在、且三个字段恒定：首次启动 / 老台账里没有 / 被删过 / 被手改过，
        // 都纠正回内置值并落盘。它是本地联调与推送测试页下拉的保底项
        PushApp builtIn = apps.get(DEFAULT_APP_ID);
        if (builtIn == null) {
            apps.put(DEFAULT_APP_ID, defaultApp());
            dirty = true;
        } else if (!DEFAULT_API_KEY.equals(builtIn.apiKey())
                || !DEFAULT_ALLOWED_MODULES.equals(builtIn.allowedModules())) {
            // 台账被人手改过：内置凭证只读，一律纠正（页面与接口本来也不许改）
            apps.put(DEFAULT_APP_ID, defaultApp());
            dirty = true;
        }
        if (dirty || !hasStore) {
            persist();
        }
        log.info("推送应用载入完成：共 {} 个{}台账 {}，鉴权开关 authEnabled={}", apps.size(),
                dirty ? "（内置默认应用 " + DEFAULT_APP_ID + " 已校正），" : "，", store.path(), authEnabled);
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

    public List<PushApp> list() {
        return List.copyOf(apps.values());
    }

    /**
     * 按 appId 定位应用；未知 appId 返回 empty（鉴权侧据此判 401）
     */
    public Optional<PushApp> find(String appId) {
        if (!StringUtils.hasText(appId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(apps.get(appId.trim()));
    }

    public boolean contains(String appId) {
        return find(appId).isPresent();
    }

    /**
     * 新增或覆盖同名应用；先落盘再进内存的顺序反过来会「内存有、磁盘没有」，
     * 落盘失败直接抛：宁可这一次改不动，也不要让人以为存住了
     */
    public void save(PushApp app) {
        synchronized (apps) {
            Map<String, PushApp> snapshot = new LinkedHashMap<>(apps);
            snapshot.put(app.appId(), app);
            store.save(List.copyOf(snapshot.values()));
            apps.put(app.appId(), app);
        }
    }

    /**
     * 内置默认应用：始终存在于台账，三个字段都不可改、整条不可删除（接口与页面都会拦，这里再兜一层）
     */
    public static boolean isBuiltIn(String appId) {
        return DEFAULT_APP_ID.equals(appId == null ? null : appId.trim());
    }

    /**
     * 内置默认应用的固定形态（启动时校正、页面展示都以此为准）
     */
    public static PushApp defaultApp() {
        return new PushApp(DEFAULT_APP_ID, DEFAULT_API_KEY, DEFAULT_ALLOWED_MODULES);
    }

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
            store.save(list());
            return true;
        }
    }

    /**
     * 把当前内存台账写回文件（供启动时播种默认应用后落盘）
     */
    private void persist() {
        store.save(list());
    }

    /**
     * 生成 apiKey：GUID（16 字节随机数）经 Base64 编码得到
     *
     * <p>取 UUID 的 128 位原始字节再编码，而不是对 {@code UUID.toString()} 的 36 字符文本编码——
     * 后者把「16 字节的随机」膨胀成 48 字节文本再编码成 48 个字符，白长了却没多一分随机性。
     *
     * <p>用 URL 安全字符集（{@code -} / {@code _}）且去掉 {@code =} 补位：16 字节 → 22 字符，
     * 不含 {@code +} / {@code /} / {@code =}，放进请求头、URL 查询参数都不必转义。
     */
    public static String generateApiKey() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
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
}
