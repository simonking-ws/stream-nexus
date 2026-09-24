package com.simonking.stream.nexus.sse.auth;

import com.simonking.stream.nexus.sse.config.SseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.util.List;

/**
 * 推送应用台账的持久化：{@code appId / apiKey / 模块白名单} 落到一个本地 JSON 文件
 *
 * <p>为什么是文件而不是 DB：推送应用是**低频写、高频读**的小台账（几条到几十条），
 * 且本服务不引入任何存储依赖——引入 DB 只为存这几行数据不划算。文件足够，
 * 且天然可读可备份（人工改完重启即生效，排障时也能直接看）。
 *
 * <p>代价要说清：**只适用于单实例**。多实例各写各的本地文件会互相覆盖认知，
 * 届时把本类换成 DB / Redis 实现即可，{@link PushAppRegistry} 与
 * {@link PushAuthInterceptor} 不受影响（它们只认内存注册表）。
 *
 * <p>写文件用「临时文件 + 原子替换」：直接覆写遇到进程被 kill / 磁盘满会留下半个文件，
 * 下次启动解析失败，整张台账就没了。
 *
 * @author simonking
 */
@Component
public class PushAppStore {

    private static final Logger log = LoggerFactory.getLogger(PushAppStore.class);

    private final ObjectMapper objectMapper;

    private final Path file;

    public PushAppStore(SseProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 存绝对路径：日志与页面提示里显示的是「真实写在哪」，相对路径容易让人找错地方。
        // 相对路径挂到**模块根目录**（nexus-sse）下，而不是进程工作目录——
        // 从仓库根启动、从模块目录启动、或 IDEA 换个工作目录，台账都落在同一处，不会分裂成两份
        this.file = resolve(properties.getAppStorePath());
    }

    /**
     * 台账路径解析：绝对路径原样采用；相对路径挂到模块根目录（nexus-sse）下
     */
    private static Path resolve(String configured) {
        Path p = Path.of(configured);
        Path base = p.isAbsolute() ? p : moduleRoot().resolve(p);
        return base.toAbsolutePath().normalize();
    }

    /**
     * 模块根目录（nexus-sse）：由本类所在的代码位置反推
     *
     * <p>IDEA / {@code spring-boot:run} 跑：{@code nexus-sse/target/classes} → 上两级 = {@code nexus-sse}；
     * 打成 jar 跑：{@code nexus-sse/target/xxx.jar} → 上两级 = {@code nexus-sse}。
     *
     * <p>推导不出来时（例如 Spring Boot 可执行 jar 的嵌套 {@code BOOT-INF/classes} 路径）
     * 退回进程工作目录，行为与改动前一致——生产部署本来就该显式配绝对路径。
     */
    private static Path moduleRoot() {
        try {
            CodeSource src = PushAppStore.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path loc = Path.of(src.getLocation().toURI());
                Path up = loc.getParent();                       // target
                Path root = up == null ? null : up.getParent();  // nexus-sse
                if (root != null) {
                    return root;
                }
            }
        } catch (Exception e) {
            // 退回工作目录
        }
        return Path.of(System.getProperty("user.dir"));
    }

    /**
     * 持久化文件的绝对路径（管理页展示用，让人知道数据落在哪）
     */
    public Path path() {
        return file;
    }

    /**
     * 文件是否已存在：决定启动时「读回台账」还是「播种内置默认应用」
     *
     * <p>与 {@link #load()} 返回空列表区分开：文件存在但列表为空 = 用户把应用删光了，
     * 这时**不能**重新播种默认应用，否则「删光后重启又被塞回一个 test」很反直觉。
     */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * 读回台账；文件不存在 / 为空 → 空列表；解析失败 → 备份原文件后返回空列表
     */
    public List<PushApp> load() {
        if (!exists()) {
            return List.of();
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("推送应用台账读取失败，按空台账启动：{}", file, e);
            return List.of();
        }
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PushApp> apps = objectMapper.readValue(json, new TypeReference<List<PushApp>>() {
            });
            return apps == null ? List.of() : apps;
        } catch (JacksonException e) {
            // 坏文件不直接丢：改名留档，人工还能抢救；同时避免下次启动反复报错
            log.error("推送应用台账不是合法 JSON，已备份为 .bad 并以空台账启动：{}", file, e);
            quarantine();
            return List.of();
        }
    }

    /**
     * 全量覆盖写回（调用方传入当前内存台账的快照）
     *
     * @throws IllegalStateException 写盘失败：内存已改而磁盘没落，属于必须让人知道的错误
     */
    public void save(List<PushApp> apps) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(apps),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 部分文件系统 / 跨卷不支持原子移动，退化成普通替换（仍比直接覆写安全）
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("推送应用台账落盘失败（内存已生效，重启会丢失）：" + file, e);
        }
    }

    /**
     * 把解析失败的文件改名留档（{@code xxx.json.bad}），不直接删
     */
    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("备份损坏的推送应用台账失败：{}", file, e);
        }
    }
}
