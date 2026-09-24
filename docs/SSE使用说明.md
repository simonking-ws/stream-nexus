# SSE 推送服务使用说明

> 模块：`nexus-sse` · 默认端口：`8088` · 技术栈：Spring Boot 4.1.1 / Java 17 / Spring MVC

`nexus-sse` 是一个**无状态扇出器（stateless fan-out）**：只负责连接管理 + 按业务模块路由 + 消息扇出，不存储业务状态、不存储消息历史；业务系统通过 HTTP 调用推送接口把消息灌进来。

---

## 消息协议

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

---

## HTTP 接口

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/sse/subscribe?modules=` | 无（可配建连鉴权） | 建立 SSE 长连接，`modules` 逗号分隔；`clientId` 由服务端分配并随建连回执下发 |
| POST | `/sse/pong?clientId=` | 无 | 心跳应答，收到 `PING` 后立即调用（`clientId` 取建连回执里的值） |
| POST | `/sse/push` | 是 | 业务系统推送入口 |
| GET | `/sse/admin/connections` | 需登录 | 连接概览：总数、模块分布、连接明细 |
| DELETE | `/sse/admin/connections/{clientId}` | 需登录 | 强制下线指定连接 |
| GET/POST/PUT/DELETE | `/sse/admin/apps` | 需登录 | 推送应用运行时增删改 |

状态码约定：

| 码 | 场景 |
| --- | --- |
| 400 | 请求体为空 |
| 401 | 缺少 `X-Sse-AppId` / `X-Sse-Key`，或二者不配对 |
| 403 | `bizModule` 不在该应用白名单内（含缺省后按 `*` 处理的情况） |
| 409 | 服务端生成的 `clientId` 撞号（UUID，理论兜底，正常路径不会走到） |
| 503 | 连接数达到 `max-connections` |

---

## 浏览器接入

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
4. `clientId` **重连即换**：要按用户维度稳定寻址，优先用模块订阅，或在业务系统侧维护「用户 → 当前 clientId」映射（页面每次建连后上报刷新）。

---

## 业务系统推送

### curl 示例

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

> `bizModule` 留空（或省略该字段）且不带 `clientIds` 时按全模块 `*` 处理，广播给全部在线连接；想定向就填 `clientIds`（此时不叠加全模块，一对一消息不会扩散）。

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

### Java 接入（Spring `RestClient`）

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

### 使用 nexus-client SDK

```java
NexusRestClient client = NexusRestClient.builder()
        .sseBaseUrl("http://localhost:8088")
        .sseAppId("test-demo")
        .sseApiKey("c3RyZWFtLW5leHVz")
        .build();

PushResult result = client.ssePush(PushRequest.builder()
        .bizModule("test")
        .action("bid")
        .data(Map.of("itemId", "L123"))
        .build());
```

更多 SDK 用法见 [SDK 接入说明](SDK接入说明.md)。

---

## 配置项

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
| `nexus.sse.admin.auth-enabled` | `true` | 是否开启管理页登录 |
| `nexus.sse.admin.username` | `admin` | 管理页登录账号 |
| `nexus.sse.admin.password` | `adminsse` | 管理页登录口令，**部署务必改掉** |
| `nexus.sse.app-store-path` | `data/push-apps.json` | 推送应用台账落盘位置（相对模块根目录），重启后仍在 |

**三套鉴权互不干涉**：`auth-enabled` 管「谁能推」、`connect-auth-enabled` 管「谁能连」、`nexus.sse.admin.*` 管「谁能打开运维页面」。

**推送应用不在这里配置**：内置默认应用 `test-demo` / `c3RyZWFtLW5leHVz`（白名单 `test`，开箱即用且**只读**：不可改、不可删），其余应用在管理页「推送应用」页签运行时增删改，改动落盘到 `nexus.sse.app-store-path`，**重启后仍在**。

---

## 连接回收机制

连接永不过期，因此死连接必须自己发现、自己回收：

| 判据 | 覆盖场景 | 兜底延迟 |
| --- | --- | --- |
| `onCompletion` / `onError` 回调 | 正常关闭、网络断开（TCP FIN/RST） | 秒级 |
| 心跳超时（90s 无 PONG） | 半开连接：进程被杀、合盖、NAT 静默丢表 | ≤90s |
| 超过 `max-lifetime` | 长期运行下的资源累积（软重置） | 按配置 |
| `send()` 抛异常 / 推送失败 | 慢消费者、缓冲区写失败 | 立即 |

---

## Docker 部署

Docker 相关文件都在 `nexus-sse/` 下，命令在 `nexus-sse/` 目录里执行：

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

- **多阶段构建**：`maven:3.9-eclipse-temurin-17` 负责打包，`eclipse-temurin:17-jre-jammy` 只跑 JRE，最终镜像不含 Maven 与源码。
- **Dockerfile 在模块内，构建上下文仍是仓库根目录**：`nexus-sse` 的父 POM 在根目录且依赖 `nexus-common`，所以脚本与 `docker-compose.yml` 都用 `-f nexus-sse/Dockerfile` + `context: ..` 的方式构建；不要在 `nexus-sse/` 下直接 `docker build .`。
- **非 root 运行**（uid 1000）、`exec java` 保证 1 号进程能收到 SIGTERM 优雅停机。
- **配置用环境变量覆盖**（Spring 宽松绑定）：`NEXUS_SSE_CONNECT_AUTH_ENABLED`、`NEXUS_SSE_HEARTBEAT_INTERVAL`、`SERVER_PORT` 等，见 `nexus-sse/docker-compose.yml`。
- 改版本号时同步 `nexus-sse/Dockerfile` 的 `ARG JAR_VERSION`。
- `.dockerignore` 必须留在仓库根目录（构建上下文根）。
- 反向代理下必须关闭缓冲，否则 SSE 消息会攒在缓冲区不下发。

反向代理（Nginx）示例：

```nginx
proxy_buffering off;
proxy_read_timeout 3600s;
proxy_set_header Connection '';
chunked_transfer_encoding on;
```

---

## 运维接口示例

```bash
# 查看在线连接
curl http://localhost:8088/sse/admin/connections

# 强制下线
curl -X DELETE http://localhost:8088/sse/admin/connections/{clientId}

# 查看推送应用
curl http://localhost:8088/sse/admin/apps

# 新增应用
curl -X POST http://localhost:8088/sse/admin/apps \
  -H 'Content-Type: application/json' \
  -d '{"appId":"x","apiKey":"y","allowedModules":["test"]}'

# 修改白名单
curl -X PUT http://localhost:8088/sse/admin/apps/{appId} \
  -H 'Content-Type: application/json' \
  -d '{"allowedModules":["test","order"]}'

# 删除应用
curl -X DELETE http://localhost:8088/sse/admin/apps/{appId}
```

> 运维接口需先登录 `/admin`（默认 `admin` / `adminsse`），获取 session 后再调用；部署生产环境务必改口令并限制内网访问。

---

## 已知限制

由「无中间件 + 极简消息体」的定位决定，接入前请确认业务可接受的取舍：

| 限制 | 影响 | 演进方向 |
| --- | --- | --- |
| 单节点 | 连接表在内存，无法水平扩展 | 连接表外置 + 跨节点广播 |
| 无消息历史 | 断线期间的消息一定丢，需 `onopen` 重拉全量 | 引入 Redis 后按 `Last-Event-ID` 补发 |
| ApiKey 明文 | 密钥可被日志落盘、请求可重放 | HMAC 签名 + nonce + 时间窗 |
| 订阅/心跳应答无鉴权 | 任何人可订阅任意模块 | JWT 订阅票据 |
| 同步扇出 | 慢消费者会阻塞推送线程 | 每连接有界出站队列 |
| 运维接口弱鉴权 | 暴露连接明细、可强制下线；`/sse/admin/apps` 还返回明文 apiKey | 已加单账号登录；仍应限制内网访问，账号体系演进方向为对接统一登录 |

明确非目标：消息必达、离线补推、跨实例路由、消息持久化、端到端加密。
