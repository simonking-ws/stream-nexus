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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 推送应用注册表：鉴权的唯一数据源
 *
 * <p>应用不在配置文件里维护：内置一个开箱即用的默认应用
 * {@value #DEFAULT_APP_ID} / {@value #DEFAULT_API_KEY}（不限制模块），
 * 其余应用在 {@code /admin} 管理页运行时增删。
 *
 * <p>代价要明确：改动只存在于内存，**重启即回到默认应用**。要持久化需外接
 * 配置中心 / DB，届时可在此处替换为对应实现，对 {@link PushAuthInterceptor} 无感。
 *
 * @author simonking
 */
@Component
public class PushAppRegistry {

    private static final Logger log = LoggerFactory.getLogger(PushAppRegistry.class);

    /**
     * 内置默认应用：本地联调开箱即用，生产环境请在管理页改成自己的 appId / key
     */
    public static final String DEFAULT_APP_ID = "test";

    public static final String DEFAULT_API_KEY = "test_secret";

    private final boolean authEnabled;

    /**
     * 按 appId 有序：管理页列表顺序稳定，不随并发写入抖动。
     * 读写比极高（每次推送都读一次），用并发容器避免读锁竞争
     */
    private final ConcurrentMap<String, PushApp> apps = new ConcurrentSkipListMap<>();

    public PushAppRegistry(SseProperties properties) {
        this.authEnabled = properties.isAuthEnabled();
        // 默认应用不限制模块，方便本地联调任意 bizModule
        apps.put(DEFAULT_APP_ID, new PushApp(DEFAULT_APP_ID, DEFAULT_API_KEY,
                List.of(NexusConstants.MODULE_WILDCARD)));
        log.info("推送应用载入完成，默认应用 {}/{}，鉴权开关 authEnabled={}", DEFAULT_APP_ID, DEFAULT_API_KEY, authEnabled);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
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
     * 新增或覆盖同名应用
     */
    public void save(PushApp app) {
        apps.put(app.appId(), app);
    }

    public boolean remove(String appId) {
        if (!StringUtils.hasText(appId)) {
            return false;
        }
        return apps.remove(appId.trim()) != null;
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
