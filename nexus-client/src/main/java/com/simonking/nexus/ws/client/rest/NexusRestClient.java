package com.simonking.nexus.ws.client.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonking.nexus.ws.client.exception.PushException;
import com.simonking.stream.nexus.common.constant.SseConstants;
import com.simonking.stream.nexus.common.constant.WsConstants;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * nexus-sse / nexus-websocket 的 REST 推送客户端（okhttp）
 *
 * <p>业务系统引入后，一次 HTTP POST 就能把消息推到浏览器，<b>不需要写任何 SSE / WebSocket 服务端代码</b>：
 * 本客户端把消息交给独立部署的推送服务，由它查终端注册表扇出到长连接。
 *
 * <pre>
 *   业务系统 --HTTP POST(本项目)--> nexus-sse       --SSE-->        浏览器
 *                              \-> nexus-websocket --WebSocket--> 浏览器
 * </pre>
 *
 * <p>builder 拼两个服务的地址与鉴权，之后<b>按终端类型挑方法</b>推送：
 * <pre>
 *     NexusRestClient client = NexusRestClient.builder()
 *             .sseBaseUrl("http://10.0.0.8:8088").sseAppId("order-service").sseApiKey("xxxxxx")
 *             .wsBaseUrl("http://10.0.0.8:8089").wsAppId("order-service").wsApiKey("xxxxxx")
 *             .build();
 *
 *     client.ssePush(request);     // 推给 SSE 终端（/sse/push）
 *     client.wsPush(request);      // 推给 WebSocket 终端（/ws/push）
 *     client.close();              // 应用退出时释放
 * </pre>
 *
 * <p><b>为什么是两个方法而不是一个</b>：SSE 与 WebSocket 是<b>两个独立部署的服务</b>
 * （各占一个端口、各自一张连接注册表与一张应用表），终端也不会同时挂在两边。
 * 合成一个 {@code push()} 就意味着要么业务方再传一个「推给谁」的参数、要么客户端替业务猜，
 * 前者多此一举，后者必错。方法名直接说明终端类型，也就不存在「推错通道、静默收不到」。
 *
 * <p><b>{@link OkHttpClient} 用默认配置</b>，不额外调参：连接复用（5 个空闲连接、保活 5 分钟）、
 * 超时（建连 / 读 / 写各 10s）、连接失败自动换路由重试都是 okhttp 的出厂值，
 * 对「偶发一次短请求」的推送场景已经够用——多一组参数就多一处要解释、要调、要背锅的地方。
 *
 * <p><b>协议定义全部取自 {@code nexus-common}</b>：接口路径与鉴权头（{@link SseConstants} / {@link WsConstants}）、
 * 推送入参与回执（{@link PushRequest} / {@link PushResult}）——服务端用的是同一份，协议漂移的风险直接归零。
 * 正因为要复用这些<b>运行期类型</b>，{@code nexus-common} 才按 Java 8 出包（见其 pom）：SDK 也是 Java 8 字节码，
 * 若 common 是 Java 17 字节码，老系统一加载就是 {@code UnsupportedClassVersionError}。
 *
 * <p><b>推送是阻塞的，且带鉴权</b>：两个服务的推送接口都要求 {@code X-*-AppId} + {@code X-*-Key}，
 * {@code push} 会等服务端处理完并返回回执，业务线程因此会阻塞一个 HTTP 往返
 * （上限是 okhttp 默认的 10s 读超时）。失败一律抛 {@link PushException}，
 * 由业务方决定是记日志丢弃还是重试——客户端不代做决定，也不知道业务语义是否幂等。
 *
 * @author simonking
 */
@Slf4j
@Builder
public class NexusRestClient implements AutoCloseable {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 默认 SSE 服务地址：本机起的 nexus-sse，端口取自 {@link SseConstants#HTTP_PORT} */
    private static final String DEFAULT_SSE_BASE_URL = "http://127.0.0.1:" + SseConstants.HTTP_PORT;

    /** 默认 WebSocket 服务地址：本机起的 nexus-websocket，端口取自 {@link WsConstants#HTTP_PORT} */
    private static final String DEFAULT_WS_BASE_URL = "http://127.0.0.1:" + WsConstants.HTTP_PORT;

    /**
     * nexus-sse 内置默认应用（见其 {@code PushAppRegistry}）：开箱即用，生产环境务必换成管理页下发的应用。
     *
     * <p>白名单只有 {@code test} 模块，推别的 bizModule 会被鉴权拦下——这是默认应用的刻意限制。
     */
    private static final String DEFAULT_SSE_APP_ID = "test-demo";

    private static final String DEFAULT_SSE_API_KEY = "c3RyZWFtLW5leHVz";

    /**
     * nexus-websocket 内置默认应用（见其 {@code PushAppRegistry}）：与 SSE 侧是两套独立注册表
     */
    private static final String DEFAULT_WS_APP_ID = "test";

    private static final String DEFAULT_WS_API_KEY = "test_secret";

    /** 错误响应体在异常消息里最多保留这么长：网关的错误页动辄几十 KB，不该整页灌进日志 */
    private static final int MAX_ERROR_BODY_LENGTH = 512;

    // ==================================================================================
    // 构造参数：两个服务各一组「地址 + 鉴权」，其余一概不调
    // ==================================================================================

    /**
     * SSE 服务根地址，如 {@code http://10.0.0.8:8088}（{@code nexus-sse} 的 {@code server.port}）
     *
     * <p>结尾的 {@code /} 会被去掉，接口路径 {@code /sse/push} 由 {@link SseConstants#PUSH_PATH} 拼上。
     */
    @Builder.Default
    private String sseBaseUrl = DEFAULT_SSE_BASE_URL;

    /** SSE 推送应用ID：由 nexus-sse 管理界面「推送应用」页签下发 */
    @Builder.Default
    private String sseAppId = DEFAULT_SSE_APP_ID;

    /** SSE 推送应用密钥，与 {@code sseAppId} 配对 */
    @Builder.Default
    private String sseApiKey = DEFAULT_SSE_API_KEY;

    /**
     * WebSocket 服务根地址，如 {@code http://10.0.0.8:8089}（{@code nexus-websocket} 的 {@code server.port}）
     *
     * <p>结尾的 {@code /} 会被去掉，接口路径 {@code /ws/push} 由 {@link WsConstants#PUSH_PATH} 拼上。
     */
    @Builder.Default
    private String wsBaseUrl = DEFAULT_WS_BASE_URL;

    /** WebSocket 推送应用ID：由 nexus-websocket 管理界面「推送应用」页签下发 */
    @Builder.Default
    private String wsAppId = DEFAULT_WS_APP_ID;

    /** WebSocket 推送应用密钥，与 {@code wsAppId} 配对 */
    @Builder.Default
    private String wsApiKey = DEFAULT_WS_API_KEY;

    // ==================================================================================
    // 运行期状态：final + 就地初始化，@Builder 不会把它们塞进 builder
    // ==================================================================================

    /** okhttp 默认配置，无参构造即可；本身就是为多实例共享设计的（自带线程池与连接池） */
    private final OkHttpClient httpClient = new OkHttpClient();

    private final ObjectMapper objectMapper = newObjectMapper();

    private final AtomicBoolean closed = new AtomicBoolean();

    // ==================================================================================
    // 推送 API：一个方法对一个服务，不存在「推错通道」
    // ==================================================================================

    /**
     * 推送给 <b>SSE 终端</b>：同步等回执，返回服务端统计的命中情况
     *
     * <p>寻址方式写在 {@link PushRequest} 里，两种可单独用也可同时用（命中并集）：
     * <ul>
     *     <li>{@code bizModule}：按业务模块广播（<b>首选</b>），终端按业务身份订阅，与连接无关，重连后依然可达；</li>
     *     <li>{@code clientIds}：定向到指定终端，ID 由服务端建连时分配、<b>重连即换</b>。</li>
     * </ul>
     * 两者都为空时无从路由，直接抛 {@link PushException}（不发请求）。
     *
     * @param request 推送入参：模块 / 终端ID / 动作 / 业务数据
     * @return 服务端回执：本次消息ID 与 {@code total / success / failed}
     * @throws PushException 地址为空 / 客户端已关闭 / 参数不合法 / 连不上 SSE 服务 / 鉴权不通过 / 序列化或回执解析失败
     */
    public PushResult ssePush(PushRequest request) {
        return post("SSE", sseBaseUrl, SseConstants.PUSH_PATH,
                SseConstants.HEADER_APP_ID, SseConstants.HEADER_API_KEY, sseAppId, sseApiKey, request);
    }

    /**
     * 推送给 <b>WebSocket 终端</b>：同步等回执，返回服务端统计的命中情况
     *
     * <p>寻址方式与 {@link #ssePush(PushRequest)} 完全一致（同一个 {@link PushRequest} 可以两边都推）。
     *
     * @param request 推送入参：模块 / 终端ID / 动作 / 业务数据
     * @return 服务端回执：本次消息ID 与 {@code total / success / failed}
     * @throws PushException 地址为空 / 客户端已关闭 / 参数不合法 / 连不上 WebSocket 服务 / 鉴权不通过 / 序列化或回执解析失败
     */
    public PushResult wsPush(PushRequest request) {
        return post("WS", wsBaseUrl, WsConstants.PUSH_PATH,
                WsConstants.HEADER_APP_ID, WsConstants.HEADER_API_KEY, wsAppId, wsApiKey, request);
    }

    /**
     * 释放：停 okhttp 的调度线程池、清连接池
     *
     * <p>实现 {@link AutoCloseable}，可以直接 try-with-resources。
     * 关闭后再推送抛 {@link PushException}——一个客户端实例不该被复用。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        log.info("[nexus-rest] 客户端已关闭");
    }

    // ==================================================================================
    // 两个服务工作完全一样，只有「地址 / 鉴权头」不同
    // ==================================================================================

    private PushResult post(String channel, String baseUrl, String pushPath,
                            String appIdHeader, String keyHeader, String appId, String apiKey,
                            PushRequest request) {
        if (closed.get()) {
            throw new PushException("客户端已关闭，不能重复使用");
        }
        if (request == null || (!hasText(request.getBizModule()) && isEmpty(request.getClientIds()))) {
            throw new PushException("bizModule 或 clientIds 必须有一个");
        }
        final String url = pushUrl(channel, baseUrl, pushPath);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new PushException(channel + " 报文序列化失败: " + e.getMessage(), e);
        }

        Request httpRequest = new Request.Builder()
                .url(url)
                .addHeader(appIdHeader, appId)
                .addHeader(keyHeader, apiKey)
                .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                .build();

        // try-with-resources：Response 必须关闭，否则连接不回池、body 不释放
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() == null ? null : response.body().string();
            if (!response.isSuccessful()) {
                // 401 = 凭证不对或应用未注册；403 = 应用没有该模块的权限；400 = 报文缺字段
                throw new PushException(channel + " 推送失败: HTTP " + response.code() + " " + response.message()
                        + " -> " + truncate(responseBody));
            }
            if (!hasText(responseBody)) {
                throw new PushException(channel + " 推送失败: 服务端返回空响应体");
            }
            try {
                return objectMapper.readValue(responseBody, PushResult.class);
            } catch (IOException e) {
                throw new PushException(channel + " 回执解析失败: " + truncate(responseBody), e);
            }
        } catch (IOException e) {
            // 连不上 / 超时 / 连接被重置：调用方自行决定记日志丢弃还是重试（推送多为幂等广播）
            throw new PushException(channel + " 推送失败: " + url + " -> " + e.getMessage(), e);
        }
    }

    private String pushUrl(String channel, String baseUrl, String pushPath) {
        if (!hasText(baseUrl)) {
            throw new PushException(channel + " baseUrl 不能为空");
        }
        String root = baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + pushPath;
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    private static ObjectMapper newObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() <= MAX_ERROR_BODY_LENGTH
                ? text
                : text.substring(0, MAX_ERROR_BODY_LENGTH) + "...(已截断)";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

}
