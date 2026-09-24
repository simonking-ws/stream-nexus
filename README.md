# Stream-Nexus

一个**轻量级实时推送服务**集合：服务端负责连接管理、按业务模块路由与消息扇出，业务系统只管把消息灌进来。

零中间件依赖（无 Redis / MQ / 数据库），全内存运行，独立部署，与业务进程解耦。

| 通道 | 模块 | 终端接入 | 业务系统接入 | 端口 |
| --- | --- | --- | --- | --- |
| SSE | `nexus-sse` | 原生 `EventSource` | HTTP `POST /sse/push` | 8088 |
| WebSocket | `nexus-websocket` | 原生 `WebSocket` | HTTP `POST /ws/push`，或 TCP 长连接（默认 9091） | 9090 / 8089 / 9091 |

- 技术栈：Spring Boot 4.1.1 / Java 17 / Spring MVC（`spring-boot-starter-webmvc`）+ Netty 4.2
- 模块：`nexus-common`（跨模块契约） + `nexus-sse`（SSE 推送服务） + `nexus-websocket`（WebSocket 推送服务） + `nexus-client`（独立客户端 SDK，两个服务都对接）
- 完整设计文档：[docs/SSE推送系统设计文档.md](docs/SSE推送系统设计文档.md)；WebSocket 通道设计原型见 [《神了，WebSocket 竟然可以这么设计！》](https://juejin.cn/post/7592079304924889098)

---

## 一、主要功能

| 功能 | 说明 |
| --- | --- |
| 长连接订阅 | `GET /sse/subscribe`，连接永不过期，一条连接可同时订阅多个业务模块；`modules` 留空默认订阅 `*` |
| 双寻址推送 | 按 `bizModule` 广播、按 `clientId` 定向，两者可同时使用（命中并集、按连接去重）；`bizModule` **留空默认全模块**（等同 `*`，广播全部连接），但带了 `clientIds` 时仍是纯定向、不扩散 |
| 全局模块 `*` | 订阅侧默认值；推 `*` = 广播给全部在线连接，推其它模块时订阅 `*` 的连接同样会收到（纯定向推送不扩散）；它与白名单里的 `*` 同形但语义不同（后者 = 不限模块） |
| 全局跨域 | `WebMvcConfigurer#addCorsMappings` 注册 `/**`，源 / 方法 / 头 / 凭证全量放行，无需配置 |
| 心跳保活 | 服务端每 15s 下发 `PING`，客户端回 `PONG`，同时压制 LB / NAT 空闲断链 |
| 死连接回收 | 四条判据（回调 / 心跳超时 / 软重置 / 写入失败）统一回收，避免半开连接堆积 |
| 推送鉴权 | `X-Sse-AppId` + `X-Sse-Key` 配对校验（常量时间比对），并按应用限制可推送的模块白名单 |
| 运维接口 | 在线连接查询与强制下线（`connections`）；推送应用 `appId`/`key` 的运行时增删（`apps`） |
| 内置页面 | Thymeleaf：`/` 直达 `/admin`（默认：连接台账 + 一键下线 + 应用管理），`/console` 推送测试页（应用下拉、内容自定义、默认模块 `test`） |
| 管理页登录 | `/admin`、`/console` 与 `/sse/admin/**` 需登录后访问（session 态），默认账号 `admin` / `adminsse`，可配置；页面顶栏「退出」登出 |

## 二、设计优势

1. **零中间件**：连接表与消息 ID 全在内存，不需要 Redis / MQ，单机 `java -jar` 即可跑，接入成本极低。
2. **永不过期 + 主动探测**：`SseEmitter(0L)` 让容器不再替你超时，配合 PING/PONG 一问一答解决 TCP 半开连接（进程被杀、笔记本合盖、NAT 静默丢表）下 `send()` 仍返回成功、死连接几小时都发现不了的难题。
3. **统一回收入口**：所有回收路径只走 `SseClientRegistry.remove()`，保证连接主表与模块倒排索引一致，杜绝索引泄漏与内存缓慢增长。
4. **极简消息体**：`NexusMessage` 固定 6 个字段。协议层字段用枚举保证稳定，业务层字段（`bizModule` / `action`）用字符串，业务方新增维度无需改公共包、无需改枚举。
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
java -jar nexus-sse/target/nexus-sse-1.0.0.jar
```

Windows 下把 `mvn` 换成 `mvnw.cmd` 即可。

### Docker 部署

Docker 相关文件都在 `nexus-sse/` 下（后续新增模块各放各的），命令都在 `nexus-sse/` 目录里执行：

```bash
cd nexus-sse

# 方式一：编排（推荐，会先构建再启动）
docker compose up -d --build

# 方式二：先打镜像再跑
./scripts/docker-build.sh       # Windows: scripts\docker-build.cmd
docker run -d --name stream-nexus -p 8088:8088 simonking/stream-nexus:1.0.0

docker compose logs -f          # 看日志
docker compose down             # 停掉
```

要点：

- **多阶段构建**：`maven:3.9-eclipse-temurin-17` 负责打包，`eclipse-temurin:17-jre-jammy` 只跑 JRE，最终镜像不含 Maven 与源码
- **Dockerfile 在模块内，构建上下文仍是仓库根目录**：`nexus-sse` 的父 POM 在根目录且依赖 `nexus-common`，所以脚本与 `docker-compose.yml` 都用 `-f nexus-sse/Dockerfile` + `context: ..` 的方式构建；不要在 `nexus-sse/` 下直接 `docker build .`
- **非 root 运行**（uid 1000）、`exec java` 保证 1 号进程能收到 SIGTERM 优雅停机
- **配置用环境变量覆盖**（Spring 宽松绑定）：`NEXUS_SSE_CONNECT_AUTH_ENABLED`、`NEXUS_SSE_HEARTBEAT_INTERVAL`、`SERVER_PORT` 等，见 `nexus-sse/docker-compose.yml`
- 改版本号时同步 `nexus-sse/Dockerfile` 的 `ARG JAR_VERSION`（或 `--build-arg JAR_VERSION=x` 与脚本的 `JAR_VERSION`）
- `.dockerignore` 必须留在仓库根目录（构建上下文根），它只对根目录生效
- 反向代理下仍需关闭缓冲，否则 SSE 消息会攒在缓冲区不下发

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

0. 访问 `http://localhost:8088/` 会自动跳转 `http://localhost:8088/admin`（默认管理页）；首次访问先跳登录页，用默认账号 `admin` / `adminsse` 登录后回跳；点导航「推送测试」进入 `http://localhost:8088/console`。
1. 在推送测试页保持默认订阅模块 `test`，点击「连接」，状态变为「已连接」。
2. 推送应用下拉默认选中内置应用 `test-demo`（自动带出 key `c3RyZWFtLW5leHVz`），业务模块默认 `test`、动作 `bid`，推送内容可直接改 JSON，点「推送」→ 日志出现 `test:bid` 及自定义字段，返回 `{"total":1,"success":1,"failed":0}`。
3. 把模块改成 `order` 再推 → `403`（默认应用白名单只有 `test`，越权模块被鉴权拦下）；换成管理页新建的、白名单含 `order` 的应用再推 → `total=0`（模块合法但无连接命中，属预期行为）。
4. 观察日志每 15s 收到一次 `PING`，Network 面板可见 `/sse/pong` 应答。
5. 访问 `http://localhost:8088/admin`（或根路径 `/`）查看在线连接台账（clientId / 业务模块 / 建连时间 / 最近心跳 / 静默时长），支持过滤、排序、自动刷新与「下线」；切到「推送应用（appId / key）」页签可新增 / 修改 / 删除应用（apiKey 留空自动生成：GUID → Base64）。

等价于
`curl http://localhost:8088/sse/admin/connections`、
`curl -X DELETE http://localhost:8088/sse/admin/connections/{clientId}`、
`curl http://localhost:8088/sse/admin/apps`、
`curl -X POST http://localhost:8088/sse/admin/apps -d '{"appId":"x","apiKey":"y","allowedModules":["test"]}'`、
`curl -X PUT http://localhost:8088/sse/admin/apps/{appId} -d '{"allowedModules":["test","order"]}'`（改模块白名单，apiKey 留空表示不改）、
`curl -X DELETE http://localhost:8088/sse/admin/apps/{appId}`。

> 内置默认应用为 `test-demo` / `c3RyZWFtLW5leHVz`（白名单 `test`），开箱即用且**整条只读**：始终存在，appId / apiKey / 白名单 都不可改、不可删除，要别的凭证请在管理页新建。管理页新增的应用会落盘到 `nexus.sse.app-store-path`（默认 `data/push-apps.json`），**重启后仍在**。

## 四、消息协议

下行消息体（`NexusMessage`，SSE 与 WebSocket 共用同一份定义）：

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
| GET | `/sse/subscribe?modules=` | 无 | 建立 SSE 长连接，`modules` 逗号分隔；`clientId` 由服务端分配并随建连回执下发 |
| POST | `/sse/pong?clientId=` | 无 | 心跳应答，收到 `PING` 后立即调用（`clientId` 取建连回执里的值） |
| POST | `/sse/push` | 是 | 业务系统推送入口 |
| GET | `/sse/admin/connections` | 无（需自行加固） | 连接概览：总数、模块分布、连接明细 |
| DELETE | `/sse/admin/connections/{clientId}` | 无（需自行加固） | 强制下线指定连接 |

状态码约定：

| 码 | 场景 |
| --- | --- |
| 400 | 请求体为空 |
| 401 | 缺少 `X-Sse-AppId` / `X-Sse-Key`，或二者不配对 |
| 403 | `bizModule` 不在该应用白名单内（含缺省后按 `*` 处理的情况） |
| 409 | 服务端生成的 `clientId` 撞号（UUID，理论兜底，正常路径不会走到） |
| 503 | 连接数达到 `max-connections` |

## 六、接入方式

### 6.1 浏览器接入

```javascript
// clientId 由服务端分配：建连时不用传，重连即换
let clientId = '';
const es = new EventSource('/sse/subscribe?modules=lot,order');

// 建连成功：必须重新拉取全量业务状态（服务端无快照、无补发）
es.onopen = () => refreshAll();

// 业务消息：按 bizModule:action 路由
es.addEventListener('MESSAGE', e => {
  const m = JSON.parse(e.data);
  if (m.bizModule === 'sse') {
    // 建连回执：服务端分配的 clientId，心跳应答与定向推送都依赖它
    if (m.action === 'connected' && m.data?.clientId) clientId = m.data.clientId;
    return;                                              // 系统消息忽略
  }
  const key = `${m.bizModule}:${m.action}:${m.data.itemId ?? ''}`;
  if (m.ts <= (lastTs[key] || 0)) return;                  // 丢弃乱序/旧消息
  lastTs[key] = m.ts;
  render(m);
});

// 心跳：收到 PING 立即回 PONG，不要在客户端自己起定时器上报
es.addEventListener('PING', () => {
  if (!clientId) return;                                   // 回执未到，ID 还没有
  fetch(`/sse/pong?clientId=${clientId}`, {method: 'POST'});
});

// 断线：什么都不用做，浏览器自动重连
es.onerror = () => {};
```

要点：

1. `clientId` 由服务端建连时分配（UUID），随建连回执下发；客户端不用生成、也不用拼进 URL。
2. `modules` 仍写进 query——浏览器自动重连会原样复用 URL，订阅模块靠它保住。
3. `es.onopen` 必须重新拉取全量状态——系统只保证在线期间的增量实时。
4. `clientId` **重连即换**：要按用户维度稳定寻址，优先用模块订阅，
   或在业务系统侧维护「用户 → 当前 clientId」映射（页面每次建连后上报刷新）。

### 6.2 业务系统推送

```bash
curl -X POST http://localhost:8088/sse/push \
  -H 'Content-Type: application/json' \
  -H 'X-Sse-AppId: test-demo' \
  -H 'X-Sse-Key: c3RyZWFtLW5leHVz' \
  -d '{
        "bizModule": "test",
        "action": "bid",
        "data": {"itemId": "L123", "currentPrice": 5200}
      }'
```

> `bizModule` 留空（或省略该字段）且不带 `clientIds` 时按全模块 `*` 处理，广播给全部在线连接；
> 想定向就填 `clientIds`（此时不叠加全模块，一对一消息不会扩散）。

响应：

```json
{"messageId":"1758123456789","total":1,"success":1,"failed":0}
```

定向推送（可同时指定，命中并集）：

```bash
curl -X POST http://localhost:8088/sse/push \
  -H 'Content-Type: application/json' \
  -H 'X-Sse-AppId: test-demo' \
  -H 'X-Sse-Key: c3RyZWFtLW5leHVz' \
  -d '{"clientIds":["<服务端建连回执下发的 clientId>"],"action":"bid","data":{"itemId":"L123"}}'
```

Java 侧调用示例（Spring `RestClient`）：

```java
restClient.post()
        .uri("http://localhost:8088/sse/push")
        .header("X-Sse-AppId", "test-demo")
        .header("X-Sse-Key", "c3RyZWFtLW5leHVz")
        .body(PushRequest.builder()
                .bizModule("test")
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
| `nexus.sse.auth-enabled` | `true` | 是否开启推送鉴权（谁能推） |
| `nexus.sse.connect-auth-enabled` | `false` | 是否开启**建连**鉴权（谁能连 `/sse/subscribe`） |
| `nexus.sse.connect-auth-token` | `57yW56iL5pyd6Iqx5aSV5ou+` | 建连令牌，所有订阅方共用；开关打开且留空时建连一律拒绝 |
| `nexus.sse.admin.auth-enabled` | `true` | 是否开启管理页登录（谁能打开运维页面、调运维接口） |
| `nexus.sse.admin.username` | `admin` | 管理页登录账号 |
| `nexus.sse.admin.password` | `adminsse` | 管理页登录口令，**部署务必改掉** |

**三套鉴权互不干涉**：`auth-enabled` 管「谁能推」、`connect-auth-enabled` 管「谁能连」、`nexus.sse.admin.*` 管「谁能打开运维页面」。登录只守 `/admin`、`/console`、`/sse/admin/**`，`/sse/subscribe` 与 `/sse/push` 不走登录态。运维接口未登录返回 401 JSON，页面未登录 302 到 `/login`（带回跳地址）。

**推送应用不在这里配置**：内置默认应用 `test-demo` / `c3RyZWFtLW5leHVz`（白名单 `test`，开箱即用且**只读**：不可改、不可删），其余应用在管理页「推送应用（appId / key）」页签运行时增删改（apiKey 可自动生成：GUID → Base64），改动经 `nexus.sse.app-store-path`（默认 `data/push-apps.json`）落盘，**重启后仍在**。

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
| 运维接口弱鉴权 | 暴露连接明细、可强制下线；`/sse/admin/apps` 还返回明文 apiKey | 已加单账号登录（默认 `admin` / `adminsse`，**部署需改口令**）；仍应限制内网访问，账号体系演进方向为对接统一登录 |

明确非目标：消息必达、离线补推、跨实例路由、消息持久化、端到端加密。

---

## 十、WebSocket 推送通道（nexus-websocket）

与 SSE 通道定位互补：SSE 是「HTTP 生态、单向、浏览器原生重连」，
WebSocket 是「全双工、可上行、适合高频交互」。两者**协议与端口完全独立**，可同时部署。

设计原型：WebSocket 服务独立部署，业务系统不写任何 Netty / WebSocket 服务端代码，
而是通过「REST 接口（或独立客户端 SDK）」把消息交给它，由它转发给终端。

```
   浏览器 / H5 / App
          │  ws://host:9090/ws?modules=test   （只带订阅模块）
          ▼
   nexus-websocket（独立部署，一个进程三组端口）
     ├── 9090  Netty WebSocket —— 终端长连接
     ├── 9091  Netty TCP       —— 业务系统长连接（连上即可推，只对内网开放）
     └── 8089  HTTP            —— REST 推送入口 / 管理界面 / 测试页 / 运维接口
          ▲
          │  HTTP POST /ws/push（短连接）或 TCP 长连接（9091）
   业务系统 ← nexus-client（或直接 HTTP 调用）
```

Netty 与 Spring MVC 在**同一进程内共享连接注册表**：REST / TCP 收到推送 → 查注册表 → 直接写 WebSocket 通道。

### TCP 接入通道（9091）

REST 推送是短连接，高频推送时每条消息都要重建 HTTP 连接、重走一次鉴权；
TCP 通道让业务系统**一条长连接一直推**，还能靠心跳提前发现链路中断。
两条链共用同一个 `WsPusher` 与同一套参数校验，只是报文换了个载体。

- **不做应用鉴权**：连接建立即可推送。TCP 端口与 REST 接口一样只对内网开放，
  「端口不暴露」就是它的边界；业务系统推的消息仍要按终端的订阅模块扇出，拿不到额外能力，
  再叠一层 appId / apiKey 只是多一套要分发、轮换、排查的凭据。
- **帧格式**：`[4 字节大端长度][UTF-8 JSON]`。不用分隔符切帧是因为报文体是 JSON，
  业务 `data` 里出现同字符就会把一个报文切成两半，且只在特定数据下偶发，极难复现。
- **报文体**：复用 `NexusMessage`，`event` 取 `TcpEvent`（`PUSH` / `RESULT` / `PING` / `PONG` / `ERROR`）。

```json
{"event":"PUSH","data":{"bizModule":"test","action":"bid","data":{"itemId":"L123"}}}
```

```json
{"event":"RESULT","bizModule":"tcp","action":"result","ts":...,"data":{"messageId":"...","total":1,"success":1,"failed":0}}
```

规则：`bizModule` / `clientIds` 都为空等参数问题只回 `ERROR`、不关连接，改完报文可以接着推；
心跳沿用 `nexus.ws.*`（写空闲 15s 服务端发 `PING`，读空闲 90s 未收到任何上行数据即判定失联并关闭）。

台账接口：`GET /ws/admin/tcp/connections`（哪些业务系统连着、推了多少条）。

业务系统侧不用自己写 Netty：`nexus-client` 里的 `NexusTcpClient` 已封装好
（Netty 实现，与服务端同一套编解码器；自带心跳、断线重连；推送不等回执，写完即返回；
SDK 按 Java 8 编译，老系统可直接引入）：

```java
try (NexusTcpClient client = NexusTcpClient.builder().host("10.0.0.8").port(9091).build()) {
    client.push(PushRequest.builder().bizModule("order").action("CREATE").data(data).build());
}
```

### 连接的唯一标识：客户端ID

由**服务端**在握手时生成（UUID），用作注册表主键与定向推送的寻址依据，
随 `CONNECTED` 回执下发给终端；前端不需要传任何标识。

```json
{"id":"...","event":"CONNECTED","bizModule":"ws","action":"connected","ts":...,"data":{"clientId":"6f1d2a3c-8b47-4e9a-9f21-0c3d5e7a1b24","modules":["test"],"heartbeatInterval":15000}}
```

- **服务端分配的代价是「重连即换」**：终端断开再连会拿到新ID。
  要按用户维度稳定寻址，优先用**模块订阅**（`?modules=xxx`），
  或在业务系统侧维护「用户 → 当前 clientId」映射（终端每次建连后上报刷新）。
- 不让客户端自带ID 是有意的：自带意味着客户端可以声明任意身份，
  服务端要么承担被冒用的风险，要么再叠一层令牌校验。
- 业务系统做定向推送时填 `clientIds`（沿用 `nexus-common` 通用契约）。

### 启动与验证

```bash
mvn -pl nexus-websocket -am package -DskipTests
java -jar nexus-websocket/target/nexus-websocket-1.0.0.jar
```

1. <http://localhost:8089/admin> 连接管理页（默认页）：在线连接台账、模块分布、强制下线、推送应用管理
2. <http://localhost:8089/console> 推送测试页：建连 → 应答心跳 → 推送 → 看日志
3. 业务系统引入 `nexus-client` 后运行 `TcpPushDemo` 的 `main`（长连接通道，无需鉴权），即可看到消息落到页面

### REST 推送接口

```bash
curl -X POST http://localhost:8089/ws/push \
  -H 'Content-Type: application/json' \
  -H 'X-Ws-AppId: test' \
  -H 'X-Ws-Key: test_secret' \
  -d '{"bizModule":"test","action":"bid","data":{"itemId":"L123"}}'
```

```json
{"messageId":"1758123456789-1","total":2,"success":2,"failed":0}
```

状态码：200 成功；400 请求体为空或 `bizModule` / `clientIds` 都为空；401 凭证不配对；403 模块不在白名单。

### WebSocket 消息体

```json
{"id":"1758123456789","event":"MESSAGE","bizModule":"test","action":"bid","ts":1758123456789,"data":{}}
```

`event`：`CONNECTED`（建连回执）/ `MESSAGE`（业务消息）/ `PING`（下行心跳）/ `PONG`（上行应答）/ `KICKED`（强制下线）。
浏览器 `WebSocket` 对象**收不到协议层 ping/pong 帧**，因此心跳必须是应用级消息：
客户端收到 `{"event":"PING"}` 必须回 `{"event":"PONG"}`，否则 90s 后被判定失联回收。

### 主要配置（`nexus.ws.*`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `ws-port` / `ws-path` | `9090` / `/ws` | WebSocket 监听端口与握手路径 |
| `server.port` | `8089` | REST 推送 / 管理界面端口，应对公网只暴露 WS，REST 只对内网开放 |
| `heartbeat-interval` / `heartbeat-timeout` | `15s` / `90s` | WS 心跳间隔与失联阈值 |
| `max-connections` | `30000` | 最大 WS 连接数，0 = 不限 |
| `tcp-port` | `9091` | TCP 接入端口（业务系统长连接）；**除端口外无独立配置**，心跳 / 单帧上限 / 连接上限 / 线程数全部沿用本表 |
| `auth-enabled` | `true` | 推送鉴权（`X-Ws-AppId` + `X-Ws-Key`），**只作用于 REST 推送，TCP 通道不鉴权** |
| `connect-auth-enabled` / `connect-auth-token` | `false` / - | 建连鉴权（查询参数 `token`） |

## 十一、目录结构

```
stream-nexus
├── pom.xml                     # 父 POM（Spring Boot 4.1.1，Java 17）
├── .dockerignore               # 构建上下文瘦身（必须留在上下文根 = 仓库根）
├── docs/                       # 设计文档
├── nexus-common/               # 跨模块契约
│   └── src/main/java/com/simonking/stream/nexus/common
│       ├── constant/
│       │   ├── NexusConstants.java          # SSE / WS 取值相同的常量（全局模块、白名单通配、建连令牌、系统动作、代理头）
│       │   ├── SseConstants.java            # SSE 协议常量（鉴权头、系统模块）
│       │   ├── WsConstants.java             # WS 协议常量（鉴权头、系统模块、关闭码）
│       │   └── TcpConstants.java            # TCP 协议常量（帧格式、鉴权字段名、系统动作）
│       ├── enums/
│       │   ├── SseEvent.java                # MESSAGE / PING / PONG
│       │   ├── WsEvent.java                 # CONNECTED / MESSAGE / PING / PONG / KICKED
│       │   └── TcpEvent.java                # AUTH / AUTH_OK / AUTH_FAIL / PUSH / RESULT / PING / PONG / ERROR
│       ├── model/                           # NexusMessage / PushRequest / PushResult
│       └── util/
│           ├── IdGenerator.java             # 单调递增消息 ID（CAS）
│           ├── IpUtils.java                 # 客户端 IP 解析（代理链 + IPv6 归一）
│           └── NexusUtils.java              # 客户端ID 生成 + 全局模块判定
└── nexus-sse/                  # 推送服务实现（端口 8088）
    ├── Dockerfile              # 多阶段构建：Maven 打包 + JRE 运行（非 root）
    ├── docker-compose.yml      # 单机编排（context: ..，配置用环境变量覆盖）
    ├── scripts/
    │   ├── docker-build.sh     # 打镜像（Linux / macOS）
    │   └── docker-build.cmd    # 打镜像（Windows）
    └── src/main/java/com/simonking/stream/nexus/sse
        ├── NexusSseApplication.java
        ├── auth/PushAuthInterceptor.java        # 推送鉴权
        ├── auth/AdminAuthFilter.java            # 管理页登录拦截（session 态）
        ├── config/SseProperties.java            # 推送 / 建连相关可调参数
        ├── config/AdminAuthProperties.java      # 管理页登录账号口令
        ├── connection/SseClient.java            # 单连接运行时状态
        ├── connection/SseClientRegistry.java    # 连接主表 + 模块索引 + 统一回收
        ├── core/SseSender.java / SsePusher.java # 单条写入 / 扇出
        ├── schedule/HeartbeatTask.java          # 心跳下发 + 连接回收
        ├── auth/PushAppRegistry.java / PushApp.java  # 推送应用运行时注册表（内置 test-demo 只读凭证 + 增删）
        ├── auth/PushAppStore.java                     # 应用台账持久化（本地 JSON 文件，重启读回）
        ├── controller/                          # 订阅 / 推送 / 运维接口 + PageController（页面跳转）+ LoginController（登录 / 登出）
        └── resources/templates/                 # Thymeleaf 页面
            ├── admin.html                       # 连接管理页（默认页：在线台账 / 强制下线）
            ├── console.html                     # 推送测试页（建连 / 推送 / 鉴权验证）
            └── login.html                       # 登录页
├── nexus-websocket/            # WebSocket 推送服务（Netty + Spring MVC，端口 9090 / 8089 / 9091）
│   └── src/main/java/com/simonking/nexus/websocket
│       ├── NettyServerRunner.java              # Netty 服务独立线程启动（WS + TCP 各占一线程）
│       ├── server/
│       │   ├── WebSocketNettyServer.java       # 9090：终端建连（握手校验 → 协议升级 → 心跳 → 业务）
│       │   ├── TcpNettyServer.java             # 9091：业务系统接入（长度帧 → 字符串 → 心跳 → 业务）
│       │   └── handler/                        # WsHandshakeHandler / WsFrameHandler / TcpFrameHandler
│       ├── registry/WsClientRegistry.java      # 终端连接主表 + 模块倒排 + 统一回收
│       ├── registry/TcpClientRegistry.java     # 业务系统接入连接表
│       ├── core/                               # WsPusher（寻址 + 扇出）/ PushService（REST 校验）/ TcpPushService（TCP 校验）
│       └── controller/                         # 页面跳转 / REST 推送 / 运维接口
└── nexus-client/               # 客户端 SDK（继承父 POM 编译，按 Java 8 出包）
    ├── README.md                               # 接入文档
    └── src/main/java/com/simonking/nexus/ws/client
        ├── rest/NexusRestClient.java           # REST 通道：ssePush / wsPush，一次 POST 同步拿回执
        ├── tcp/NexusTcpClient.java             # 客户端本体：Lombok builder 拼参数，push() 直接推
        ├── tcp/TcpClientHandler.java           # 回收执 / 答应心跳 / 发现链路断开
        └── exception/PushException.java
        # 协议定义一行都不自己写：事件 / 报文体 / 入参 / 回执全部复用 nexus-common
        # （所以 common 按 Java 8 出包，且其 spring 依赖为 optional）
```
