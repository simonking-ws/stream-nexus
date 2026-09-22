package com.simonking.stream.nexus.sse.connection;

import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SSE 连接
 *
 * <p>永不过期模式下，连接是否被回收取决于：
 * <ol>
 *     <li>{@code onCompletion} / {@code onError} 回调（正常关闭，覆盖绝大多数场景）；</li>
 *     <li>{@link #lastPongTime} 超过 heartbeatTimeout（半开连接、客户端被冻结等异常场景的唯一证明）；</li>
 *     <li>超过 {@link #createTime} + maxLifetime（软重置）；</li>
 *     <li>写入失败（慢消费者 / 连接已死）。</li>
 * </ol>
 *
 * @author simonking
 */
@Data
public class SseClient {

    /**
     * 客户端ID（服务端建连时生成，见
     * {@link com.simonking.stream.nexus.common.constant.SseConstants#newClientId()}）
     *
     * <p>随连接生命周期变化：重连即换。定向推送的寻址依据，业务系统需自行维护它与用户的映射。
     */
    private String clientId;

    /**
     * SSE 发射器
     */
    private SseEmitter emitter;

    /**
     * 订阅的业务模块，如 lot / order。按模块推送的路由依据
     *
     * <p>并发安全：回收线程会遍历该集合，故选用 {@link CopyOnWriteArraySet}（读多写少）
     */
    private final Set<String> modules;

    /**
     * 客户端 IP（建连时从请求解析，支持 X-Forwarded-For / X-Real-IP 代理头）
     */
    private final String ip;

    /**
     * 建连时间
     */
    private long createTime;

    /**
     * 最后一次收到客户端心跳应答（PONG）的时间
     */
    private volatile long lastPongTime;

    public SseClient(String clientId, SseEmitter emitter, Set<String> modules, String ip) {
        this.clientId = clientId;
        this.emitter = emitter;
        this.modules = new CopyOnWriteArraySet<>(modules);
        this.ip = ip;
        this.createTime = System.currentTimeMillis();
        this.lastPongTime = this.createTime;
    }
}
