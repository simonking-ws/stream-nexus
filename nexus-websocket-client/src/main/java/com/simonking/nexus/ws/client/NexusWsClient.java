package com.simonking.nexus.ws.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonking.nexus.ws.client.exception.PushException;
import com.simonking.nexus.ws.client.model.PushRequest;
import com.simonking.nexus.ws.client.model.PushResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * nexus-websocket 独立客户端：一次 HTTP POST 就是一次推送
 *
 * <p>业务项目只需要这一段代码就能拥有推送能力，完全不用碰 Netty 与 WebSocket：
 * <pre>{@code
 * NexusWsClient client = NexusWsClient.builder()
 *         .host("10.0.0.8").port(8089)
 *         .appId("order-service").apiKey("xxxxxx")
 *         .buildClient();
 * client.push(PushRequest.of("order", "CREATE", Map.of("orderId", 1001)));
 * }</pre>
 *
 * <p>寻址方式有二：按业务模块广播（终端建连时用 {@code modules} 订阅），
 * 或按 <b>clientId</b> 定向。clientId 由<b>服务端</b>在握手时生成（UUID），
 * 随建连回执（{@code CONNECTED}）下发给终端，终端再上报给业务系统。
 * 注意它<b>重连即换</b>：要按用户维度稳定寻址请用模块订阅，
 * 或在业务系统侧维护「用户 → 当前 clientId」的映射。
 *
 * <p>为什么是 HTTP 而不是长连接：业务系统到推送服务之间是「低频、可信、内网」的调用，
 * 每次推送建一次 HTTP 请求远比维护一条长连接划算——
 * 不用管注册、心跳、重连、半开连接，出问题也能直接 curl 复现。
 * 代价是每条消息多一次 TCP 握手，对推送这种量级完全可以忽略。
 *
 * <p>三条关键约定：
 * <ol>
 *     <li>无状态：本类线程安全，做成单例随应用启动创建即可，不需要 connect()；</li>
 *     <li>同步 {@link #push(PushRequest)} 直接返回 {@code PushResult}（命中 / 成功 / 失败数），
 *         失败抛 {@link PushException}；异步用 {@link #pushAsync(PushRequest)}；</li>
 *     <li>鉴权走请求头 {@code X-Ws-AppId} / {@code X-Ws-Key}，
 *         与服务端 {@code PushAppRegistry} 里的应用一一对应。</li>
 * </ol>
 *
 * @author simonking
 */
public final class NexusWsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NexusWsClient.class);

    private static final String HEADER_APP_ID = "X-Ws-AppId";

    private static final String HEADER_API_KEY = "X-Ws-Key";

    private final ClientOptions options;

    private final ObjectMapper mapper;

    private final HttpClient http;

    private final URI endpoint;

    private final AtomicBoolean closed = new AtomicBoolean();

    NexusWsClient(ClientOptions options) {
        this.options = options;
        this.mapper = new ObjectMapper();
        this.endpoint = URI.create("http://" + options.host() + ":" + options.port() + normalizePath(options.pushPath()));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(options.connectTimeoutMs(), 1)))
                .build();
        log.info("nexus-websocket 客户端就绪, endpoint={}, appId={}", endpoint, options.appId());
    }

    public static ClientOptions.Builder builder() {
        return ClientOptions.builder();
    }

    /**
     * 同步推送：直接返回本次命中 / 成功 / 失败的连接数
     *
     * @throws PushException 序列化失败、网络异常、超时，或服务端返回非 200
     */
    public PushResult push(PushRequest request) {
        HttpRequest httpRequest = buildRequest(request);
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return handle(response);
        } catch (IOException e) {
            throw new PushException("push failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("push interrupted", e);
        }
    }

    /**
     * 按模块广播
     */
    public PushResult push(String bizModule, String action, Object data) {
        return push(PushRequest.of(bizModule, action, data));
    }

    /**
     * 定向推送：只推给指定客户端
     */
    public PushResult pushTo(List<String> clientIds, String action, Object data) {
        return push(PushRequest.to(clientIds, action, data));
    }

    /**
     * 定向推送单个连接
     */
    public PushResult pushTo(String clientId, String action, Object data) {
        return push(PushRequest.to(clientId, action, data));
    }

    /**
     * 异步推送：不阻塞业务线程，失败同样以 {@link PushException} 完成
     */
    public CompletableFuture<PushResult> pushAsync(PushRequest request) {
        HttpRequest httpRequest = buildRequest(request);
        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::handle);
    }

    /**
     * 异步推送：按模块广播
     */
    public CompletableFuture<PushResult> pushAsync(String bizModule, String action, Object data) {
        return pushAsync(PushRequest.of(bizModule, action, data));
    }

    public ClientOptions options() {
        return options;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 标记客户端不可用。
     *
     * <p>JDK 的 {@link HttpClient} 由守护线程驱动、随 JVM 退出，没有必须显式释放的资源，
     * 这里只是让后续调用快速失败，避免应用关闭后还在往已经下线的服务推消息。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("nexus-websocket 客户端已关闭, endpoint={}", endpoint);
        }
    }

    private HttpRequest buildRequest(PushRequest request) {
        ensureOpen();
        if (request == null) {
            throw new PushException("request must not be null");
        }
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new PushException("serialize push request failed", e);
        }
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(Math.max(options.pushTimeoutMs(), 1)))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header(HEADER_APP_ID, options.appId())
                .header(HEADER_API_KEY, options.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private PushResult handle(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status < 200 || status >= 300) {
            // 把服务端给的原文带上：401 / 403 / 400 的原因都在里面，丢了就得去翻服务端日志
            throw new PushException("push rejected: HTTP " + status + " " + body);
        }
        try {
            JsonNode node = mapper.readTree(body);
            return new PushResult(text(node, "messageId"), number(node, "total"),
                    number(node, "success"), number(node, "failed"));
        } catch (JsonProcessingException e) {
            throw new PushException("bad push response: " + body, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? 0 : value.asInt();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/ws/push";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new PushException("client is closed");
        }
    }
}
