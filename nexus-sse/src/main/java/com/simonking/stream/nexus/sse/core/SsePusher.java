package com.simonking.stream.nexus.sse.core;

import com.simonking.stream.nexus.common.constant.NexusConstants;
import com.simonking.stream.nexus.common.enums.SseEvent;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import com.simonking.stream.nexus.common.model.NexusMessage;
import com.simonking.stream.nexus.common.util.IdGenerator;
import com.simonking.stream.nexus.common.util.NexusUtils;
import com.simonking.stream.nexus.sse.connection.SseClient;
import com.simonking.stream.nexus.sse.connection.SseClientRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 推送门面
 *
 * <p>两种寻址方式：按业务模块广播、按客户端定向。同时指定时取并集并按 clientId 去重。
 * 全局模块 {@link NexusConstants#GLOBAL_MODULE} 参与两侧路由，见 {@link #resolveTargets(PushRequest)}。
 *
 * <p>{@code bizModule} 缺省已在
 * {@link com.simonking.stream.nexus.sse.controller.PushController} 归一化为 {@code *}（纯定向除外），
 * 因此这里拿到的 {@code bizModule} 为空时一定是一次纯定向推送。
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsePusher {

    private final SseClientRegistry registry;

    private final SseSender sender;

    /**
     * 扇出一条消息（并行发送）
     *
     * <p>各连接之间无顺序依赖，用 {@code parallelStream()} 并行写入，避免单条慢连接（网络拥塞、
     * 客户端读得慢）拖住整批扇出。计数用 {@link LongAdder}——高并发累加下比 {@code AtomicInteger}
     * 竞争更小。
     *
     * <p>并行依赖两处线程安全：{@link SseClientRegistry} 的容器是并发容器（回收可安全并发调用）；
     * 消息对象在发送前构建完成，全程只读。
     *
     * <p>写入失败的连接立即回收，避免无效连接长期占用内存与文件句柄。
     *
     * <p>注意：并行流跑在 JVM 共享的 {@code ForkJoinPool.commonPool}（默认线程数 = CPU 核数 - 1）。
     * 连接数远大于核数时这里会排队，且慢连接会占用公共池线程，影响同进程内其它并行流 /
     * {@code CompletableFuture}。若要隔离，改为自定义 ForkJoinPool 提交即可（接口不变）。
     */
    public PushResult push(PushRequest request) {
        Collection<SseClient> targets = resolveTargets(request);

        NexusMessage<Object, SseEvent> message = NexusMessage.<Object, SseEvent>builder()
                .id(IdGenerator.nextId())
                .event(SseEvent.MESSAGE)
                .bizModule(request.getBizModule())
                .action(request.getAction())
                .ts(System.currentTimeMillis())
                .data(request.getData())
                .build();

        LongAdder success = new LongAdder();
        LongAdder failed = new LongAdder();
        targets.parallelStream().forEach(client -> {
            try {
                sender.send(client, message);
                success.increment();
            } catch (Exception e) {
                failed.increment();
                log.warn("[sse] send failed, clientId={}, bizModule={}, msgId={}",
                        client.getClientId(), request.getBizModule(), message.getId());
                registry.remove(client.getClientId());
            }
        });

        return PushResult.builder()
                .messageId(message.getId())
                .total(targets.size())
                .success(success.intValue())
                .failed(failed.intValue())
                .build();
    }

    /**
     * 解析目标连接：模块命中 ∪ 全局模块订阅者 ∪ 定向命中，按 clientId 去重
     *
     * <p>全局模块（{@code *}）的两条规则：
     * <ul>
     *     <li>推送目标就是 {@code *} → 广播给全部在线连接（{@link SseClientRegistry#all()}）；</li>
     *     <li>推送目标是其它模块 → 额外带上订阅了 {@code *} 的连接，
     *         保证 {@code modules} 留空的客户端不会「静默收不到」。</li>
     * </ul>
     *
     * <p>纯定向推送（只填 clientIds）不叠加全局模块，避免一对一消息扩散给无关连接。
     */
    private Collection<SseClient> resolveTargets(PushRequest request) {
        boolean byModule = StringUtils.hasText(request.getBizModule());
        boolean byClient = !CollectionUtils.isEmpty(request.getClientIds());

        if (!byModule && !byClient) {
            return List.of();
        }

        Map<String, SseClient> result = new LinkedHashMap<>();

        if (byModule) {
            Collection<SseClient> moduleClients = NexusUtils.isGlobal(request.getBizModule())
                    ? registry.all()
                    : registry.byModule(request.getBizModule());
            for (SseClient client : moduleClients) {
                result.putIfAbsent(client.getClientId(), client);
            }
            for (SseClient client : registry.byModule(NexusConstants.GLOBAL_MODULE)) {
                result.putIfAbsent(client.getClientId(), client);
            }
        }

        if (byClient) {
            for (SseClient client : registry.byClientIds(request.getClientIds())) {
                result.putIfAbsent(client.getClientId(), client);
            }
        }
        return result.values();
    }
}
