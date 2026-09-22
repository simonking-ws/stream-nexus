package com.simonking.nexus.ws.client;

/**
 * 客户端配置
 *
 * <p>本 SDK 只做一件事：把 {@code PushRequest} 序列化后 POST 给服务端的 REST 推送接口。
 * 没有长连接、没有注册、没有心跳，因此配置项比长连接方案少得多——
 * 真正需要调的通常只有 {@code host / port / appId / apiKey} 四项。
 *
 * @param host             推送服务地址（HTTP 端口，默认 8089 对应的 host）
 * @param port             推送服务 HTTP 端口，对应服务端 {@code server.port}，默认 8089
 * @param appId            推送应用ID，对应请求头 {@code X-Ws-AppId}
 * @param apiKey           推送应用密钥，对应请求头 {@code X-Ws-Key}
 * @param pushPath         推送接口路径，需与服务端 {@code POST /ws/push} 一致
 * @param connectTimeoutMs 建连超时（毫秒）
 * @param pushTimeoutMs    单次推送超时（毫秒）
 * @author simonking
 */
public record ClientOptions(String host, int port, String appId, String apiKey,
                            String pushPath, long connectTimeoutMs, long pushTimeoutMs) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String host = "127.0.0.1";

        private int port = 8089;

        private String appId = "test";

        private String apiKey = "test_secret";

        private String pushPath = "/ws/push";

        private long connectTimeoutMs = 3_000L;

        private long pushTimeoutMs = 5_000L;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder pushPath(String pushPath) {
            this.pushPath = pushPath;
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder pushTimeoutMs(long pushTimeoutMs) {
            this.pushTimeoutMs = pushTimeoutMs;
            return this;
        }

        public ClientOptions build() {
            return new ClientOptions(host, port, appId, apiKey, pushPath, connectTimeoutMs, pushTimeoutMs);
        }

        /**
         * 直接构造客户端实例
         */
        public NexusWsClient buildClient() {
            return new NexusWsClient(build());
        }
    }
}
