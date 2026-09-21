# Stream-Nexus

一个基于 SSE（Server-Sent Events）的**轻量级实时推送服务**：业务系统通过 HTTP 把消息灌进来，服务端负责连接管理、按业务模块路由与消息扇出，浏览器用原生 `EventSource` 接收。

零中间件依赖（无 Redis / MQ / 数据库），全内存运行，独立部署，与业务进程解耦。

- 技术栈：Spring Boot 4.1.1 / Java 17 / Spring MVC（`spring-boot-starter-webmvc`）
- 模块：`nexus-common`（跨模块契约） + `nexus-sse`（推送服务实现，默认端口 8088）
- 完整设计文档：[docs/SSE推送系统设计文档.md](docs/SSE推送系统设计文档.md)

---

## 一、主要功能

| 功能 | 说明 |
| --- | --- |
| 长连接订阅 | `GET /sse/subscribe`，连接永不过期，一条连接可同时订阅多个业务模块；`modules` 留空默认订阅 `global` |
| 双寻址推送 | 按 `bizModule` 广播、按 `clientId` 定向，两者可同时使用（命中并集、按连接去重） |
| 全局模块 `global` | 订阅侧默认值；推 `global` = 广播给全部在线连接，推其它模块时订阅 `global` 的连接同样会收到（纯定向推送不扩散） |
| 全局跨域 | `WebMvcConfigurer#addCorsMappings` 注册 `/**`，源 / 方法 / 头 / 凭证全量放行，无需配置 |
| 心跳保活 | 服务端每 15s 下发 `PING`，客户端回 `PONG`，同时压制 LB / NAT 空闲断链 |
| 死连接回收 | 四条判据（回调 / 心跳超时 / 软重置 / 写入失败）统一回收，避免半开连接堆积 |
| 推送鉴权 | `X-Sse-AppId` + `X-Sse-Key` 配对校验（常量时间比对），并按应用限制可推送的模块白名单 |
| 运维接口 | 在线连接查询与强制下线（`connections`）；推送应用 `appId`/`key` 的运行时增删（`apps`） |
| 内置页面 | Thymeleaf：`/` 直达 `/admin`（默认：连接台账 + 一键下线 + 应用管理），`/console` 推送测试页（应用下拉、内容自定义、默认模块 `test`） |

## 二、设计优势

1. **零中间件**：连接表与消息 ID 全在内存，不需要 Redis / MQ，单机 `java -jar` 即可跑，接入成本极低。
2. **永不过期 + 主动探测**：`SseEmitter(0L)` 让容器不再替你超时，配合 PING/PONG 一问一答解决 TCP 半开连接（进程被杀、笔记本合盖、NAT 静默丢表）下 `send()` 仍返回成功、死连接几小时都发现不了的难题。
3. **统一回收入口**：所有回收路径只走 `SseClientRegistry.remove()`，保证连接主表与模块倒排索引一致，杜绝索引泄漏与内存缓慢增长。
4. **极简消息体**：`SseMessage` 固定 6 个字段。协议层字段用枚举保证稳定，业务层字段（`bizModule` / `action`）用字符串，业务方新增维度无需改公共包、无需改枚举。
5. **契约与实现分离**：`nexus-common` 只放契约（消息体、枚举、常量、推送入参出参），业务系统可单独依赖它，不必引入服务端实现。
6. **精细的推送鉴权**：`X-Sse-AppId` 定位应用、`X-Sse-Key` 证明身份，二者必须配对；每个应用可配 `allowed-modules` 白名单，越权推其他模块返回 403。
7. **连接复用**：一条 SSE 连接可订阅多个 `bizModule`，规避浏览器同域 HTTP/1.1 的 6 连接上限。

## 三、快速开始

### 环境要求

- JDK 17+
- Maven 3.8+（或直接使用仓库自带的 `mvnw` / `mvnw.cmd`）

### 构建与启动

```bash
# 编译打包（跳过测试）
mvn clean package -DskipTests

# 启动推送服务
mvn -pl nexus-sse -am spring-boot:run

# 或直接运行 jar
java -jar nexus-sse/target/nexus-sse-1.0.0-SNAPSHOT.jar
```

Windows 下把 `mvn` 换成 `mvnw.cmd` 即可。

### 跨域

全局跨域已内置在 `WebMvcConfig#addCorsMappings`，作用于 `/**`，源 / 方法 / 头 / 凭证全量放行，**无需任何配置**：

```java
registry.addMapping("/**")
        .allowedOriginPatterns("*")   // 凭证模式下 allowedOrigins 不接受 "*"，必须用 pattern 形式
        .allowedMethods("*")
        .allowedHeaders("*")          // 放开才能带 X-Sse-AppId / X-Sse-Key
        .allowCredentials(true)
        .maxAge(3600);
```

`/sse/push` 的 `OPTIONS` 预检不带鉴权头，已在 `PushAuthInterceptor` 中放行，否则浏览器侧跨域推送会被 401 挡掉。

> 跨域只负责「让浏览器连得上」，不承载鉴权；真正的权限控制在推送鉴权与模块白名单。

### 冒烟验证

0. 访问 `http://localhost:8088/` 会自动跳转 `http://localhost:8088/admin`（默认管理页）；点导航「推送测试」进入 `http://localhost:8088/console`。
1. 在推送测试页保持默认订阅模块 `test`，点击「连接」，状态变为「已连接」。
2. 推送应用下拉默认选中内置应用 `test`（自动带出 key `test_secret`），业务模块默认 `test`、动作 `bid`，推送内容可直接改 JSON，点「推送」→ 日志出现 `test:bid` 及自定义字段，返回 `{"total":1,"success":1,"failed":0}`。
3. 把模块改成未订阅的 `order` 再推 → `total=0`（无连接命中，属预期行为）。
4. 观察日志每 15s 收到一次 `PING`，Network 面板可见 `/sse/pong` 应答。
5. 访问 `http://localhost:8088/admin`（或根路径 `/`）查看在线连接台账（clientId / 业务模块 / 建连时间 / 最近心跳 / 静默时长），支持过滤、排序、自动刷新与「下线」；切到「推送应用（appId / key）」页签可新增 / 删除应用（apiKey 留空自动生成 GUID）。

等价于
`curl http://localhost:8088/sse/admin/connections`、
`curl -X DELETE http://localhost:8088/sse/admin/connections/{clientId}`、
`curl http://localhost:8088/sse/admin/apps`、
`curl -X POST http://localhost:8088/sse/admin/apps -d '{"appId":"x","apiKey":"y","allowedModules":["test"]}'`、
`curl -X PUT http://localhost:8088/sse/admin/apps/{appId} -d '{"allowedModules":["test","order"]}'`（改模块白名单，apiKey 留空表示不改）、
`curl -X DELETE http://localhost:8088/sse/admin/apps/{appId}`。

> 内置默认应用为 `test` / `test_secret`（白名单 `*`），开箱即用；应用不在配置文件里，管理页新增的应用**仅内存生效，重启回到内置默认应用**。

## 四、消息协议

下行消息体（`SseMessage`）：

```json
{
  "id": "1758123456789",
  "event": "MESSAGE",
  "bizModule": "lot",
  "action": "bid",
  "ts": 1758123456789,
  "data": { "itemId": "L123", "currentPrice": 5200 }
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 单调递增的消息 ID，对应 SSE 的 `id:` 行，用于 `Last-Event-ID` 续传与客户端乱序丢弃；**心跳消息不带 id** |
| `event` | 协议层枚举：`MESSAGE`（下行业务消息）/ `PING`（下行心跳）/ `PONG`（上行心跳应答） |
| `bizModule` | 业务模块，**同时是路由键**；订阅侧与推送侧取值必须完全一致（含大小写），否则静默推空 |
| `action` | 业务动作，业务方自定义，如 `bid` / `create` |
| `ts` | 服务端时间戳（毫秒），客户端据此丢弃乱序与旧消息 |
| `data` | 业务数据；多订阅场景下**必须携带归属标识**（如 `itemId` / `orderId`）供客户端二次路由 |

模块名 `sse` 为系统保留（建连通知），业务方不得占用。

## 五、HTTP 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/sse/subscribe?clientId=&modules=` | 无 | 建立 SSE 长连接，`modules` 逗号分隔 |
| POST | `/sse/pong?clientId=` | 无 | 心跳应答，收到 `PING` 后立即调用 |
| POST | `/sse/push` | 是 | 业务系统推送入口 |
| GET | `/sse/admin/connections` | 无（需自行加固） | 连接概览：总数、模块分布、连接明细 |
| DELETE | `/sse/admin/connections/{clientId}` | 无（需自行加固） | 强制下线指定连接 |

状态码约定：

| 码 | 场景 |
| --- | --- |
| 400 | `bizModule` 与 `clientIds` 都为空 |
| 401 | 缺少 `X-Sse-AppId` / `X-Sse-Key`，或二者不配对 |
| 403 | `bizModule` 不在该应用白名单内 |
| 409 | `clientId` 已在线（重连窗口） |
| 503 | 连接数达到 `max-connections` |

## 六、接入方式

### 6.1 浏览器接入

```javascript
const clientId = crypto.randomUUID();   // 整个页面生命周期内保持不变（含自动重连）
const es = new EventSource(`/sse/subscribe?clientId=${clientId}&modules=lot,order`);

// 建连成功：必须重新拉取全量业务状态（服务端无快照、无补发）
es.onopen = () => refreshAll();

// 业务消息：按 bizModule:action 路由
es.addEventListener('MESSAGE', e => {
  const m = JSON.parse(e.data);
  if (m.bizModule === 'sse') return;                       // 系统消息忽略
  const key = `${m.bizModule}:${m.action}:${m.data.itemId ?? ''}`;
  if (m.ts <= (lastTs[key] || 0)) return;                  // 丢弃乱序/旧消息
  lastTs[key] = m.ts;
  render(m);
});

// 心跳：收到 PING 立即回 PONG，不要在客户端自己起定时器上报
es.addEventListener('PING', () => {
  fetch(`/sse/pong?clientId=${clientId}`, {method: 'POST'});
});

// 断线：什么都不用做，浏览器自动重连
es.onerror = () => {};
```

要点：

1. `clientId` 必须写进 query 且全程不变，否则重连后订阅的模块会丢失。
2. `es.onopen` 必须重新拉取全量状态——系统只保证在线期间的增量实时。
3. 定向推送所需的 `clientId` 由页面建连后上报给业务系统，由业务系统自行维护 `clientId ↔ userId` 映射。

### 6.2 业务系统推送

```bash
curl -X POST http://localhost:8088/sse/push \
  -H 'Content-Type: application/json' \
  -H 'X-Sse-AppId: test' \
  -H 'X-Sse-Key: test_secret' \
  -d '{
        "bizModule": "lot",
        "action": "bid",
        "data": {"itemId": "L123", "currentPrice": 5200}
      }'
```

响应：

```json
{"messageId":"1758123456789","total":1,"success":1,"failed":0}
```

定向推送（可同时指定，命中并集）：

```bash
curl -X POST http://localhost:8088/sse/push \
  -H 'Content-Type: application/json' \
  -H 'X-Sse-AppId: test' \
  -H 'X-Sse-Key: test_secret' \
  -d '{"clientIds":["<页面生成的 clientId>"],"action":"bid","data":{"itemId":"L123"}}'
```

Java 侧调用示例（Spring `RestClient`）：

```java
restClient.post()
        .uri("http://localhost:8088/sse/push")
        .header("X-Sse-AppId", "test")
        .header("X-Sse-Key", "test_secret")
        .body(PushRequest.builder()
                .bizModule("lot")
                .action("bid")
                .data(Map.of("itemId", "L123", "currentPrice", 5200))
                .build())
        .retrieve()
        .body(PushResult.class);
```

## 七、配置项

`nexus-sse/src/main/resources/application.properties`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8088` | 服务端口 |
| `spring.mvc.async.request-timeout` | `0` | 异步请求不过期，与 `SseEmitter(0L)` 成对配置，改动需三处同步 |
| `nexus.sse.heartbeat-interval` | `15s` | 服务端下发 `PING` 的间隔 |
| `nexus.sse.heartbeat-timeout` | `90s` | 多久没收到 `PONG` 判定失联（应 ≥ 3 倍心跳间隔） |
| `nexus.sse.max-lifetime` | `0` | 连接最大存活时间（软重置），0 表示不限制 |
| `nexus.sse.max-connections` | `30000` | 最大连接数，0 表示不限制 |
| `nexus.sse.auth-enabled` | `true` | 是否开启推送鉴权 |

**推送应用不在这里配置**：内置默认应用 `test` / `test_secret`（白名单 `*`，开箱即用），其余应用在管理页「推送应用（appId / key）」页签运行时增删（apiKey 可自动生成 GUID），改动**仅内存生效，重启回到内置默认应用**。

反向代理（Nginx）下必须关闭缓冲，否则消息会攒在缓冲区不下发：

```nginx
proxy_buffering off;
proxy_read_timeout 3600s;
proxy_set_header Connection '';
chunked_transfer_encoding on;
```

## 八、连接回收机制

连接永不过期，因此死连接必须自己发现、自己回收：

| 判据 | 覆盖场景 | 兜底延迟 |
| --- | --- | --- |
| `onCompletion` / `onError` 回调 | 正常关闭、网络断开（TCP FIN/RST） | 秒级 |
| 心跳超时（90s 无 PONG） | 半开连接：进程被杀、合盖、NAT 静默丢表 | ≤90s |
| 超过 `max-lifetime` | 长期运行下的资源累积（软重置） | 按配置 |
| `send()` 抛异常 / 推送失败 | 慢消费者、缓冲区写失败 | 立即 |

## 九、已知限制

由「无中间件 + 极简消息体」的定位决定，接入前请确认业务可接受的取舍：

| 限制 | 影响 | 演进方向 |
| --- | --- | --- |
| 单节点 | 连接表在内存，无法水平扩展 | 连接表外置 + 跨节点广播 |
| 无消息历史 | 断线期间的消息一定丢，需 `onopen` 重拉全量 | 引入 Redis 后按 `Last-Event-ID` 补发 |
| ApiKey 明文 | 密钥可被日志落盘、请求可重放 | HMAC 签名 + nonce + 时间窗 |
| 订阅/心跳应答无鉴权 | 任何人可订阅任意模块 | JWT 订阅票据 |
| 同步扇出 | 慢消费者会阻塞推送线程 | 每连接有界出站队列 |
| 运维接口无鉴权 | 暴露连接明细、可强制下线；`/sse/admin/apps` 还返回明文 apiKey | 加鉴权并限制内网访问 |

明确非目标：消息必达、离线补推、跨实例路由、消息持久化、端到端加密。

## 十、目录结构

```
stream-nexus
├── pom.xml                     # 父 POM（Spring Boot 4.1.1，Java 17）
├── docs/                       # 设计文档
├── nexus-common/               # 跨模块契约
│   └── src/main/java/com/simonking/stream/nexus/common
│       ├── constant/SseConstants.java     # 协议层常量（鉴权头、系统模块、通配符）
│       ├── enums/EventEnum.java           # MESSAGE / PING / PONG
│       ├── model/                         # SseMessage / PushRequest / PushResult
│       └── util/IdGenerator.java          # 单调递增消息 ID（CAS）
└── nexus-sse/                  # 推送服务实现（端口 8088）
    └── src/main/java/com/simonking/stream/nexus/sse
        ├── NexusSseApplication.java
        ├── auth/PushAuthInterceptor.java        # 推送鉴权
        ├── config/SseProperties.java            # 全部可调参数
        ├── connection/SseClient.java            # 单连接运行时状态
        ├── connection/SseClientRegistry.java    # 连接主表 + 模块索引 + 统一回收
        ├── core/SseSender.java / SsePusher.java # 单条写入 / 扇出
        ├── schedule/HeartbeatTask.java          # 心跳下发 + 连接回收
        ├── auth/PushAppRegistry.java / PushApp.java  # 推送应用运行时注册表（内置 test/test_secret + 增删）
        ├── controller/                          # 订阅 / 推送 / 运维接口 + PageController（页面跳转）
        └── resources/templates/                 # Thymeleaf 页面
            ├── admin.html                       # 连接管理页（默认页：在线台账 / 强制下线）
            └── console.html                     # 推送测试页（建连 / 推送 / 鉴权验证）
```
