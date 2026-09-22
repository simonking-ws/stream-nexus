# nexus-websocket-client

`nexus-websocket` 推送服务的**独立客户端 SDK**。业务项目引入它之后，一行代码就能把消息推到浏览器，
**完全不需要编写任何 Netty / WebSocket 服务端代码**。

> 设计原型见 [《神了，WebSocket 竟然可以这么设计！》](https://juejin.cn/post/7592079304924889098)：
> WebSocket 服务独立部署，业务系统通过「Socket 中间客户端」把消息交给它，再由它转发给终端。
> 本项目在实现上把中间通道简化成了 **REST**：见 [§5 为什么是 HTTP](#5-为什么是-http-而不是长连接)。

## 1. 它解决什么问题

传统做法里，每个业务系统都要复制一份 WebSocket 服务端代码（`ServerBootstrap`、握手、ChannelGroup……），
N 个系统就是 N 份重复实现。这里把它收敛成一个独立服务：

```
   浏览器 / H5 / App
          │  WebSocket（只负责建连、收消息）
          ▼
   nexus-websocket 服务（独立部署）
     ├── :9090  Netty WebSocket —— 终端长连接
     └── :8089  HTTP            —— REST 推送入口 / 管理界面 / 测试页
          ▲
          │  HTTP POST /ws/push（只负责推消息）
   业务系统 ← nexus-websocket-client（本项目）
```

两个端口在同一进程内共享连接注册表：REST 收到推送 → 查注册表 → 直接写 WebSocket 通道。

## 2. 独立性

本模块**没有父 POM、不依赖任何 `nexus-*` 模块**（不依赖 Spring、不依赖 Netty），全部依赖只有两个：

| 依赖 | 用途 |
|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | 请求 / 响应 JSON 序列化 |
| `org.slf4j:slf4j-api` | 日志门面 |

HTTP 客户端直接用 JDK 自带的 `java.net.http.HttpClient`（Java 11+），不再引入任何网络库。

协议类（`PushRequest` / `PushResult`）在本模块内**独立定义**，
不与服务端共享代码——跨进程只共享「契约」，不共享「类」，这样服务端升级不会波及业务系统。

## 3. 快速开始

### 3.1 引入

```xml
<dependency>
    <groupId>com.simonking.nexus</groupId>
    <artifactId>nexus-websocket-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.2 推送

```java
// 1. 构造客户端（无状态、线程安全，建议做成单例，随应用启动创建；不需要 connect()）
NexusWsClient client = NexusWsClient.builder()
        .host("10.0.0.8")
        .port(8089)
        .appId("order-service")
        .apiKey("xxxxxxxx")
        .buildClient();

// 2. 推送
//    按模块广播：所有订阅了 order 模块的连接都会收到
client.push("order", "CREATE", Map.of("orderId", 1001, "amount", 99.9));

//    定向推送：只推给指定客户端（clientId 由服务端建连时分配，终端上报给业务系统）
client.pushTo(List.of("6f1d2a3c-8b47-4e9a-9f21-0c3d5e7a1b24"), "PAID", Map.of("orderId", 1001));

//    异步推送
client.pushAsync(PushRequest.of("order", "CREATE", data))
      .thenAccept(result -> log.info("命中 {} 条", result.total()));

// 3. 应用关闭时释放
client.close();
```

`push()` 是**同步**的：内部等待 HTTP 响应（默认 5 秒），超时或服务端返回非 2xx 抛 `PushException`。

### 3.3 寻址：模块 vs 客户端ID

终端建连只需带订阅模块（`ws://host:9090/ws?modules=test`）；客户端ID 由**服务端**在握手时生成（UUID），
随建连回执 `CONNECTED` 下发给终端，终端再上报给业务系统。

| 方式 | 用法 | 适用场景 |
| --- | --- | --- |
| 按模块广播 | `client.push("order", ...)` | **首选**。终端按业务身份订阅模块，与连接无关，重连后依然可达 |
| 按客户端定向 | `client.pushTo(List.of(clientId), ...)` | 一次性回执、点对点通知；注意 clientId **重连即换**，需由终端建连后重新上报 |

## 4. REST 接口契约

```
POST http://host:8089/ws/push
Content-Type: application/json
X-Ws-AppId: order-service
X-Ws-Key:   xxxxxxxx

{"bizModule":"order","action":"CREATE","clientIds":["..."],"data":{...}}
```

```json
{"messageId":"1758123456789-1","total":2,"success":2,"failed":0}
```

| 状态码 | 含义 |
|---|---|
| 200 | 成功，`body` 即 `PushResult`（命中 / 成功 / 失败数） |
| 400 | 请求体为空，或 `bizModule` 与 `clientIds` 都为空 |
| 401 | `X-Ws-AppId` / `X-Ws-Key` 不配对 |
| 403 | `bizModule` 不在该应用的模块白名单内 |

SDK 已封装好这些：非 2xx 一律转成 `PushException`，并把服务端原文带在异常消息里。

不想引入 SDK 也可以直接调用：

```bash
curl -X POST http://localhost:8089/ws/push \
  -H 'Content-Type: application/json' \
  -H 'X-Ws-AppId: test' -H 'X-Ws-Key: test_secret' \
  -d '{"bizModule":"test","action":"bid","data":{"itemId":"L123"}}'
```

## 5. 为什么是 HTTP 而不是长连接

业务系统到推送服务之间是**低频、可信、内网**的调用，每次推送建一次 HTTP 请求远比维护长连接划算：

| 维度 | 长连接（原方案） | REST（现方案） |
|---|---|---|
| 客户端复杂度 | 注册、心跳、重连、半开连接检测、EventLoop 死锁防护 | 无状态，一次 POST |
| 依赖 | 需要 Netty | 无（JDK 自带） |
| 排障 | 要看 TCP 状态机与 ACK 时序 | 直接 curl 复现 |
| 可观测性 | 需额外埋点 | 走 HTTP 访问日志 |
| 代价 | — | 每条消息多一次 TCP 握手（内网可忽略） |

## 6. 配置项（ClientOptions.Builder）

| 方法 | 默认值 | 说明 |
|---|---|---|
| `host` / `port` | `127.0.0.1` / `8089` | 服务端 HTTP 地址与端口（对应 `server.port`） |
| `appId` / `apiKey` | `test` / `test_secret` | 推送应用凭证，在管理界面可增删 |
| `pushPath` | `/ws/push` | 推送接口路径 |
| `connectTimeoutMs` | `3000` | 建连超时 |
| `pushTimeoutMs` | `5000` | 单次推送（等待响应）超时 |

## 7. 联调

1. 启动 `nexus-websocket`（`mvn -pl nexus-websocket spring-boot:run`）；
2. 打开 <http://localhost:8089/console>，点「连接」建立 WebSocket；
3. 运行 `src/test/java/.../PushDemo.java` 的 `main`，每 2 秒推一条消息，页面日志即可看到落地；
4. 打开 <http://localhost:8089/admin> 查看在线连接、模块分布。

## 8. Spring 集成示例

```java
@Configuration
public class WsClientConfig {

    @Bean(destroyMethod = "close")
    public NexusWsClient nexusWsClient(@Value("${nexus.ws.host}") String host,
                                       @Value("${nexus.ws.port:8089}") int port,
                                       @Value("${nexus.ws.app-id}") String appId,
                                       @Value("${nexus.ws.api-key}") String apiKey) {
        return NexusWsClient.builder()
                .host(host).port(port)
                .appId(appId).apiKey(apiKey)
                .buildClient();
    }
}
```

> 客户端是**无状态**的：构造即就绪，不需要 `connect()`，服务端短暂不可用也不影响应用启动。
> 单次推送失败会抛 `PushException`，是否重试由业务自行决定（推送多为幂等广播，建议失败即丢弃或记日志）。
