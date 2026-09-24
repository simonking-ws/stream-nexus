# nexus-client SDK 接入说明

`nexus-sse` / `nexus-websocket` 推送服务的**客户端 SDK**：一次 HTTP POST 或一条 TCP 长连接就能把消息推到浏览器，
**完全不需要编写任何 SSE / Netty / WebSocket 服务端代码**。

> 设计原型见 [《神了，WebSocket 竟然可以这么设计！》](https://juejin.cn/post/7592079304924889098)：
> 推送服务独立部署，业务系统通过「中间客户端」把消息交给它，再由它转发给终端。

## 1. 它解决什么问题

传统做法里，每个业务系统都要复制一份长连接服务端代码（`SseEmitter` / `ServerBootstrap`、握手、ChannelGroup……），
N 个系统就是 N 份重复实现。这里把它们收敛成两个独立部署的推送服务：

```
   浏览器 / H5 / App                 浏览器 / H5 / App
          │  SSE                           │  WebSocket
          ▼                                ▼
   nexus-sse（8088）              nexus-websocket（8089 / 9090 / 9091）
     ├── :8088  HTTP     —— REST 推送 / 管理界面 / 测试页
     │                        ├── :8089  HTTP            —— REST 推送 / 管理界面 / 测试页
     │                        ├── :9090  Netty WebSocket —— 终端长连接
     │                        └── :9091  Netty TCP       —— 业务系统长连接
          ▲                                ▲
          │  REST（/sse/push）或 REST（/ws/push）/ TCP 长连接（本项目都给）
   业务系统
```

两个服务各自独立：各占一个端口、各一张连接注册表、各一张推送应用表，互不影响，
也因此**推送入口按终端类型分开**：`ssePush` 打 8088、`wsPush` 打 8089。

本项目提供**REST 与 TCP 两类通道**（同一客户端不一定二选一，可并存）：

| 客户端 | 通道 | 特点 |
| --- | --- | --- |
| `NexusRestClient` | HTTP 8088 / 8089 | 一次 POST，**同步拿回执**（`total / success / failed`），不用维护连接，跨语言、过网关最省事 |
| `NexusTcpClient` | TCP 9091 | 一条长连接复用，**发后不管**，高频推送开销更低；代价是要维护连接与重连（只有 WebSocket 服务提供） |

日常用 REST（推没推到人当场就知道），高频场景（行情、日志流）换 TCP。

## 2. 模块怎么组织的

- **编译交给父 POM**：`<parent>` 指向 `stream-nexus`（其父是 `spring-boot-starter-parent`），
  插件版本、依赖版本一律继承；本模块只覆盖 `java.version=8`
  ——这是给别的业务系统引入的 SDK，按 Java 8 出包老系统才能直接用（代码也一直按 Java 8 语法写：
  不用 `record` / `var` / 箭头 switch / `java.net.http`）。
  正因如此，REST 通道选的是 okhttp 而不是 JDK 11 的 `java.net.http.HttpClient`——后者在 Java 8 上根本不存在；
  okhttp 4.x 本身也是按 Java 8 字节码发的，与 SDK 的目标版本一致。
- **参数用 Lombok `@Builder`**，日志用 `@Slf4j`：都是编译期注解，不进运行期依赖。
- 运行期依赖只有五个：

| 依赖 | 用途 |
| --- | --- |
| `io.netty:netty-all` | TCP 通道：长连接的 IO 与帧编解码 |
| `com.squareup.okhttp3:okhttp` | REST 通道：一次 HTTP POST 拿回执（Spring Boot 不管它，版本由父 POM 定） |
| `com.fasterxml.jackson.core:jackson-databind` | 报文 JSON 序列化 / 回执反序列化 |
| `org.slf4j:slf4j-api` | 日志门面 |
| `nexus-common` | 协议契约：常量 + 事件枚举 + 报文体 + 推送入参/回执 |

**协议定义全部复用 `nexus-common`**，与服务端用的是同一份，协议漂移的风险直接归零：

| 复用 | 内容 |
| --- | --- |
| `TcpConstants` | 帧格式（长度字段偏移 / 字节数 / 剥离字节），改帧头等价于改协议 |
| `TcpConstants` | 连接层默认数值：端口 9091 / 心跳 15s / 服务端判死 90s / 客户端判死 30s / 单帧 64KB，服务端 `WsProperties` 的默认值也取自这里 |
| `TcpEvent` | 事件枚举：`PUSH / RESULT / PING / PONG / ERROR` |
| `NexusMessage<T, E>` | 报文体，客户端用 `NexusMessage<Object, TcpEvent>` |
| `PushRequest` | 推送入参（回执 `PushResult`：TCP 通道由服务端下发、客户端只记日志；REST 通道直接作为返回值） |
| `WsConstants` | WebSocket 服务：接口路径 `/ws/push`、鉴权头 `X-Ws-AppId` / `X-Ws-Key`、默认 HTTP 端口 8089 |
| `SseConstants` | SSE 服务：接口路径 `/sse/push`、鉴权头 `X-Sse-AppId` / `X-Sse-Key`、默认 HTTP 端口 8088 |

> **为此 `nexus-common` 按 Java 8 出包**（它显式覆盖 `java.version=8`，父 POM 是 17）。
> 原因：这些是运行期类型，SDK 也是 Java 8 字节码，若 common 是 Java 17 字节码，
> 老系统第一次推送就会 `UnsupportedClassVersionError`。
> 代价是 common 从此不能出现 Java 9+ 的语法与 API，而且它的 `spring-boot-starter` 是 optional 的
> ——只复用「常量 + 模型」的 SDK 不会把 Spring 带给业务系统。

源码一共 4 个类（两个客户端，共用一个异常）：

```
com.simonking.nexus.ws.client
├── rest/NexusRestClient.java      # REST 客户端：ssePush / wsPush，一次 POST 同步拿回执
├── tcp/NexusTcpClient.java        # TCP 客户端：builder 拼参数 + push 就推（长连接）
├── tcp/TcpClientHandler.java      # 报文处理器：记回执 / 答应心跳 / 发现链路断开
└── exception/PushException.java
```

## 3. 快速开始

### 3.1 引入

```xml
<dependency>
    <groupId>com.simonking.nexus</groupId>
    <artifactId>nexus-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.2 推送（REST，推荐）

```java
// 1. 构造：两个服务各一组「地址 + 鉴权」，用哪个填哪个（只推 SSE 就可以不写 ws*）
NexusRestClient client = NexusRestClient.builder()
        .sseBaseUrl("http://10.0.0.8:8088").sseAppId("order-service").sseApiKey("sse-key")
        .wsBaseUrl("http://10.0.0.8:8089").wsAppId("order-service").wsApiKey("ws-key")
        .build();

// 2. 推：按终端类型挑方法，一次 HTTP POST，同步拿回执
Map<String, Object> data = new HashMap<>();
data.put("orderId", 1001);
data.put("amount", 99.9);
PushRequest request = PushRequest.builder().bizModule("order").action("CREATE").data(data).build();

PushResult sseResult = client.ssePush(request);   // 推给 SSE 终端（POST /sse/push）
PushResult wsResult = client.wsPush(request);     // 推给 WebSocket 终端（POST /ws/push）
System.out.println("命中 " + wsResult.getTotal() + " 条，成功 " + wsResult.getSuccess() + " 条");

// 3. 应用退出时释放（停调度线程、清连接池）
client.close();
```

**两个方法各打一个服务**，因为 SSE 与 WebSocket 本来就是两个独立部署的服务（端口不同、
各自一张连接注册表和一张推送应用表），终端也不会同时挂在两边。合成一个 `push()` 只会让调用方
要么多传一个「推给谁」的参数、要么由客户端替业务猜——前者多此一举，后者必错。

**同步等回执**：两个方法都会阻塞一个 HTTP 往返，返回值就是服务端的统计结果
（`messageId / total / success / failed`）——推没推到人当场就知道，不用另开回执通道
（`total=0` 表示推成功了但没人在线，与「没推成功」是两回事）。
阻塞上限是 okhttp 默认的 10s 读超时，服务卡住不会把调用方无限挂起。

**失败即抛 `PushException`**（不做回调、不返回 null：要不要重试只有业务知道），
异常消息里带是哪个通道 + 状态码 + 响应体（截断到 512 字符）：

| 场景 | 表现 |
| --- | --- |
| 参数为空 / 报文序列化失败 / `baseUrl` 为空 | 不发请求，直接抛 |
| 连不上、超时、连接被重置 | `IOException` 包成 `PushException` |
| 凭证不对（401）/ 模块越权（403）/ 报文缺字段（400） | 抛 `PushException`，消息里带 HTTP 状态码 |

> **okhttp 用默认配置**（`new OkHttpClient()`，不调参）：10s 建连 / 读 / 写超时、
> 5 个空闲连接保活 5 分钟、连接失败自动换路由重试——对「偶发一次短请求」的推送足够。
> 不幂等的推送请自行判重：自动重试只对「连接层面」的失败生效（连不上、连接被复用方关闭），
> 服务端已收到的请求不会重发。真要定制超时等参数，业务系统自行持有 `OkHttpClient` 发请求即可，
> 协议就三步：`POST baseUrl + /sse/push 或 /ws/push`、两个鉴权头、请求体 / 响应体分别是
> `PushRequest` / `PushResult` 的 JSON。

**鉴权必填**：两个服务的推送接口都走 HTTP 端口（可能经网关暴露），分别要求
`X-Sse-AppId` + `X-Sse-Key`（SSE）与 `X-Ws-AppId` + `X-Ws-Key`（WebSocket）。
builder 里的默认值是两个服务各自内置的默认应用 `test / test_secret`（开箱即用），
**生产环境务必换成各管理界面「推送应用」页签下发的应用**。

### 3.3 推送（TCP 长连接）

```java
// 1. 构造：参数是 builder 拼的，host / port 都可以不写（默认 127.0.0.1:9091）
NexusTcpClient client = NexusTcpClient.builder()
        .host("10.0.0.8")
        .build();

// 2. 推：整个 SDK 只有 push(PushRequest) 一个入口，不用先 connect()
Map<String, Object> data = new HashMap<>();
data.put("orderId", 1001);
data.put("amount", 99.9);

client.push(PushRequest.builder().bizModule("order").action("CREATE").data(data).build());   // 按模块广播
client.push(PushRequest.builder()
        .clientIds(Arrays.asList("6f1d2a3c-8b47-4e9a-9f21-0c3d5e7a1b24"))
        .action("PAID").data(data).build());                                                  // 定向指定终端

// 3. 应用退出时释放（断连接、停心跳与重连线程）
client.close();
```

**推送不等回执**：`push()` 把报文写进连接就返回，不等服务端处理完，业务线程不会被推送拖住。
服务端照旧下发 `RESULT`（命中多少条连接），客户端只在 debug 日志里记一笔，不再做请求—回执配对。

只有「还没发出去」的错误才会抛 `PushException`：参数为空、连不上推送服务、报文序列化失败。
报文一旦交给 `writeAndFlush` 就**不监听结果**——发送成功与否无人感知（调用方早已返回，异常没人接），
链路问题由读空闲判定 + 重连线程兜底，与这一帧无关。

**不做应用鉴权**：TCP 端口只对内网开放，连上即可推，因此没有 appId / apiKey——
「端口不暴露」就是它的边界。

### 3.4 寻址：模块 vs 客户端ID

终端建连只需带订阅模块（`ws://host:9090/ws?modules=test`）；客户端ID 由**服务端**在握手时生成（UUID），
随建连回执 `CONNECTED` 下发给终端，终端再上报给业务系统。

| 方式 | 用法 | 适用场景 |
| --- | --- | --- |
| 按模块广播 | `PushRequest.builder().bizModule("order")...` | **首选**。终端按业务身份订阅模块，与连接无关，重连后依然可达 |
| 按客户端定向 | `PushRequest.builder().clientIds(ids)...` | 一次性回执、点对点通知；注意 clientId **重连即换**，需由终端建连后重新上报 |

两者可同时填（命中并集）；都为空时无从路由，`ssePush` / `wsPush` 直接抛 `PushException`。

## 4. TCP 通道细节

> 本节只描述 **TCP 长连接**（`NexusTcpClient`）。REST 通道没有帧格式 / 心跳 / 重连这些概念：
> 它就是一次普通的 HTTP POST（okhttp 复用连接池），请求体是 `PushRequest` 的 JSON，
> 响应体是 `PushResult` 的 JSON，连接管理交给 okhttp。

**帧格式**：`[4 字节大端长度][UTF-8 JSON]`（常量取自服务端的 `TcpConstants`）。
不用分隔符切帧是因为报文体是 JSON，业务 `data` 里出现同字符就会把一个报文切成两半，
且只在特定数据下偶发，极难复现。

**报文体**：即服务端的 `NexusMessage`，`event` 取 `TcpEvent`：

```json
{"event":"PUSH","data":{"bizModule":"order","action":"CREATE","data":{"orderId":1001}}}
```

```json
{"event":"RESULT","bizModule":"tcp","action":"result","ts":...,"data":{"messageId":"...","total":1,"success":1,"failed":0}}
```

**连接是怎么维护的**：

| 机制 | 行为 |
| --- | --- |
| 心跳 | 写空闲 15s 主动发 PING；收到服务端 PING 自动回 PONG |
| 失效判定 | 30s 未收到任何下行数据即判定链路半开，自动重连（3s 一次） |
| 发送顺序 | 并发调用 `push()` 是安全的：`Channel.writeAndFlush` 按调用顺序排队到 EventLoop 上发送 |
| 失败语义 | 参数类错误只回 ERROR，连接保持，改完报文可以接着推；回执不参与配对，也就没有「回执错配」 |

**实现基于 Netty**（与服务端同一套编解码器）：

```
Bootstrap -> NioSocketChannel
   LengthFieldBasedFrameDecoder / LengthFieldPrepender   （长度前缀切帧）
   StringDecoder / StringEncoder                          （UTF-8）
   IdleStateHandler                                       （写空闲发 PING，读空闲判死）
   TcpClientHandler                                       （记 RESULT / ERROR，答 PING）
```

客户端是**单 EventLoop 线程**模型，因此重连不能在 EventLoop 线程里做：
`connect().sync()` 会把 EventLoop 自己等死，断线后由独立的 `nexus-tcp-reconnect` 线程按间隔重试。

## 5. 构造参数（builder）

### 5.1 REST（`NexusRestClient`）

两个服务各一组「地址 + 鉴权」，用哪个填哪个；其余全是 okhttp 默认配置：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `sseBaseUrl` | `http://127.0.0.1:8088` | SSE 服务根地址；结尾 `/` 会被去掉，`/sse/push` 自动拼上 |
| `sseAppId` / `sseApiKey` | `test` / `test_secret` | SSE 服务的推送应用凭证 |
| `wsBaseUrl` | `http://127.0.0.1:8089` | WebSocket 服务根地址；结尾 `/` 会被去掉，`/ws/push` 自动拼上 |
| `wsAppId` / `wsApiKey` | `test` / `test_secret` | WebSocket 服务的推送应用凭证 |

> 两组参数<b>不共享</b>：两个服务各有一张推送应用表，凭证各自独立，
> 只在 SSE 注册过的应用去推 WebSocket 会得到 401（反之亦然）。

### 5.2 TCP（`NexusTcpClient`）

除了 host / port，都有默认值，日常不用管：

| 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | `127.0.0.1` | 服务端地址 |
| `port` | `9091` | 服务端 TCP 端口（`nexus.ws.tcp-port`），不是 HTTP 的 8089 |
| `connectTimeoutMs` | `3000` | 建连超时 |
| `heartbeatIntervalMs` | `15000` | 写空闲多久主动发 PING；0 = 不主动探活（仍应答服务端 PING） |
| `heartbeatTimeoutMs` | `30000` | 多久没收到下行数据即判定失效并重连 |
| `autoReconnect` / `reconnectIntervalMs` | `true` / `3000` | 断线自动重连与重试间隔 |
| `maxFrameLength` | `65536` | 单帧报文体上限（字节），与服务端 `max-frame-length` 同值 |

心跳那两个数与服务端 `nexus.ws.heartbeat-*`（15s / 90s）是配套关系：
客户端按 15s 发声就不会被服务端判死，30s 收不到下行就自己重连——
**必须小于服务端的 90s**，否则「服务端已关、客户端还在旧连接上等」。

## 6. 联调

1. 启动两个推送服务（各开一个终端）：
   `mvn -pl nexus-sse spring-boot:run`（8088）与 `mvn -pl nexus-websocket spring-boot:run`（8089）；
2. 各自打开测试页点「连接」（都默认订阅 `test` 模块）：
   SSE 是 <http://localhost:8088/console>，WebSocket 是 <http://localhost:8089/console>；
3. 运行 `src/test/java/.../RestPushDemo.java` 的 `main`，每 2 秒往两个服务的 `test` 模块各推一条，
   控制台分别打印 SSE / WS 回执（`total / success / failed`），两个页面都能看到落地；
   只起了一个服务时，另一路会立即抛 `PushException`，互不干扰；
   换成 `TcpPushDemo.java` 就是 TCP 长连接版的演示（只有 WebSocket 服务有 TCP 通道）；
4. 打开各自的管理页 <http://localhost:8088/admin> / <http://localhost:8089/admin>
   查看在线连接与模块分布（TCP 通道的业务系统连接在「TCP 接入」页签）。

## 7. Spring 集成示例

```java
@Configuration
public class WsClientConfig {

    // REST：一次 POST 拿回执，日常首选；不用的那个服务留空即可（留默认地址不影响另一路）
    @Bean(destroyMethod = "close")
    public NexusRestClient nexusRestClient(@Value("${nexus.sse.base-url}") String sseBaseUrl,
                                           @Value("${nexus.sse.app-id}") String sseAppId,
                                           @Value("${nexus.sse.api-key}") String sseApiKey,
                                           @Value("${nexus.ws.base-url}") String wsBaseUrl,
                                           @Value("${nexus.ws.app-id}") String wsAppId,
                                           @Value("${nexus.ws.api-key}") String wsApiKey) {
        return NexusRestClient.builder()
                .sseBaseUrl(sseBaseUrl).sseAppId(sseAppId).sseApiKey(sseApiKey)
                .wsBaseUrl(wsBaseUrl).wsAppId(wsAppId).wsApiKey(wsApiKey)
                .build();
    }

    // TCP：高频场景用（不需要就只留上面那个 bean）
    @Bean(destroyMethod = "close")
    public NexusTcpClient nexusTcpClient(@Value("${nexus.ws.tcp-host}") String host,
                                         @Value("${nexus.ws.tcp-port:9091}") int port) {
        NexusTcpClient client = NexusTcpClient.builder().host(host).port(port).build();
        // 可选：启动时就连上，让「服务没起 / 地址填错」在启动阶段暴露，而不是等到第一次推送
        client.connect();
        return client;
    }
}
```

> REST 客户端是**无状态**的：没有连接要建，实例只持有 okhttp（连接池 + 线程池），
> 进程内共享一个单例即可，随用随推。

> 服务端短暂不可用不影响启动：连不上时 `connect()` 抛 `PushException`，
> 之后由重连线程按 `reconnectIntervalMs` 自动重试；这期间的推送会立即失败，不会积压。
> 单次推送失败是否重试由业务自行决定（推送多为幂等广播，建议失败即丢弃或记日志）。
