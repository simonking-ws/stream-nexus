# nexus-websocket-client

`nexus-websocket` 推送服务的**客户端 SDK**：业务系统建一条 TCP 长连接就能把消息推到浏览器，
**完全不需要编写任何 Netty / WebSocket 服务端代码**。

> 设计原型见 [《神了，WebSocket 竟然可以这么设计！》](https://juejin.cn/post/7592079304924889098)：
> WebSocket 服务独立部署，业务系统通过「Socket 中间客户端」把消息交给它，再由它转发给终端。

## 1. 它解决什么问题

传统做法里，每个业务系统都要复制一份 WebSocket 服务端代码（`ServerBootstrap`、握手、ChannelGroup……），
N 个系统就是 N 份重复实现。这里把它收敛成一个独立服务：

```
   浏览器 / H5 / App
          │  WebSocket
          ▼
   nexus-websocket 服务（独立部署）
     ├── :9090  Netty WebSocket —— 终端长连接，只负责建连与收消息
     ├── :9091  Netty TCP       —— 业务系统长连接，只负责把消息交进来
     └── :8089  HTTP            —— 管理界面 / 测试页
          ▲
          │  TCP 长连接（本项目）
   业务系统
```

两个 Netty 端口在同一进程内共享连接注册表：TCP 侧收到推送 → 查注册表 → 直接写 WebSocket 通道。

## 2. 模块怎么组织的

- **编译交给父 POM**：`<parent>` 指向 `stream-nexus`（其父是 `spring-boot-starter-parent`），
  插件版本、依赖版本一律继承；本模块只覆盖 `java.version=8`
  ——这是给别的业务系统引入的 SDK，按 Java 8 出包老系统才能直接用（代码也一直按 Java 8 语法写：
  不用 `record` / `var` / 箭头 switch / `java.net.http`）。
- **参数用 Lombok `@Builder`**，日志用 `@Slf4j`：都是编译期注解，不进运行期依赖。
- 运行期依赖只有四个：

| 依赖 | 用途 |
| --- | --- |
| `io.netty:netty-all` | 长连接的 IO 与帧编解码 |
| `com.fasterxml.jackson.core:jackson-databind` | 报文 JSON 序列化 |
| `org.slf4j:slf4j-api` | 日志门面 |
| `nexus-common` | 协议契约：常量 + 事件枚举 + 报文体 + 推送入参/回执 |

**协议定义全部复用 `nexus-common`**，与服务端用的是同一份，协议漂移的风险直接归零：

| 复用 | 内容 |
| --- | --- |
| `TcpConstants` | 帧格式（长度字段偏移 / 字节数 / 剥离字节），改帧头等价于改协议 |
| `TcpConstants` | 连接层默认数值：端口 9091 / 心跳 15s / 服务端判死 90s / 客户端判死 30s / 单帧 64KB，服务端 `WsProperties` 的默认值也取自这里 |
| `TcpEvent` | 事件枚举：`PUSH / RESULT / PING / PONG / ERROR` |
| `NexusMessage<T, E>` | 报文体，客户端用 `NexusMessage<Object, TcpEvent>` |
| `PushRequest` | 推送入参（回执 `PushResult` 由服务端下发，客户端不再消费，只记日志） |

> **为此 `nexus-common` 按 Java 8 出包**（它显式覆盖 `java.version=8`，父 POM 是 17）。
> 原因：这些是运行期类型，SDK 也是 Java 8 字节码，若 common 是 Java 17 字节码，
> 老系统第一次推送就会 `UnsupportedClassVersionError`。
> 代价是 common 从此不能出现 Java 9+ 的语法与 API，而且它的 `spring-boot-starter` 是 optional 的
> ——只复用「常量 + 模型」的 SDK 不会把 Spring 带给业务系统。

源码一共 3 个类：

```
com.simonking.nexus.ws.client
├── tcp/NexusTcpClient.java        # 客户端本体：builder 拼参数 + push 就推
├── tcp/TcpClientHandler.java      # 报文处理器：记回执 / 答应心跳 / 发现链路断开
└── exception/PushException.java
```

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

### 3.3 寻址：模块 vs 客户端ID

终端建连只需带订阅模块（`ws://host:9090/ws?modules=test`）；客户端ID 由**服务端**在握手时生成（UUID），
随建连回执 `CONNECTED` 下发给终端，终端再上报给业务系统。

| 方式 | 用法 | 适用场景 |
| --- | --- | --- |
| 按模块广播 | `PushRequest.builder().bizModule("order")...` | **首选**。终端按业务身份订阅模块，与连接无关，重连后依然可达 |
| 按客户端定向 | `PushRequest.builder().clientIds(ids)...` | 一次性回执、点对点通知；注意 clientId **重连即换**，需由终端建连后重新上报 |

两者可同时填（命中并集）；都为空时无从路由，`push` 直接抛 `PushException`。

## 4. 通道细节

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

1. 启动 `nexus-websocket`（`mvn -pl nexus-websocket spring-boot:run`）；
2. 打开 <http://localhost:8089/console>，点「连接」建立 WebSocket（默认订阅 `test` 模块）；
3. 运行 `src/test/java/.../TcpPushDemo.java` 的 `main`，每 2 秒推一条到 `test` 模块，页面日志即可看到落地；
4. 打开 <http://localhost:8089/admin> 查看在线连接、模块分布（业务系统的连接在「TCP 接入」页签）。

## 7. Spring 集成示例

```java
@Configuration
public class WsClientConfig {

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

> 服务端短暂不可用不影响启动：连不上时 `connect()` 抛 `PushException`，
> 之后由重连线程按 `reconnectIntervalMs` 自动重试；这期间的推送会立即失败，不会积压。
> 单次推送失败是否重试由业务自行决定（推送多为幂等广播，建议失败即丢弃或记日志）。
