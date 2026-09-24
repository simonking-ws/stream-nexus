# WebSocket 推送服务使用说明

> 模块：`nexus-websocket` · 默认端口：WebSocket `9090` / HTTP `8089` / TCP `9091` · 技术栈：Spring Boot 4.1.1 / Java 17 / Netty 4.2

`nexus-websocket` 与 SSE 通道定位互补：SSE 是「HTTP 生态、单向、浏览器原生重连」，WebSocket 是「全双工、可上行、适合高频交互」。两者**协议与端口完全独立**，可同时部署。

设计原型：WebSocket 服务独立部署，业务系统不写任何 Netty / WebSocket 服务端代码，而是通过「REST 接口（或 TCP 长连接）」把消息交给它，由它转发给终端。

---

## 架构与端口

```
   浏览器 / H5 / App
          │  ws://host:9090/ws?modules=test
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

---

## 启动与验证

```bash
mvn -pl nexus-websocket -am package -DskipTests
java -jar nexus-websocket/target/nexus-websocket-1.0.0.jar
```

或使用仓库自带 wrapper：

```bash
mvnw.cmd -pl nexus-websocket -am package -DskipTests   # Windows
./mvnw -pl nexus-websocket -am package -DskipTests     # Linux / macOS
```

验证：

1. 打开 `http://localhost:8089/console` 推送测试页，默认订阅模块 `test`，点击「连接」。
2. 选择默认应用 `test` / `test_secret`，点「推送」→ 页面收到 `test:bid` 消息。
3. 打开 `http://localhost:8089/admin` 查看在线连接台账、模块分布、强制下线、应用管理。

---

## REST 推送接口

### curl 示例

```bash
curl -X POST http://localhost:8089/ws/push \
  -H 'Content-Type: application/json' \
  -H 'X-Ws-AppId: test' \
  -H 'X-Ws-Key: test_secret' \
  -d '{"bizModule":"test","action":"bid","data":{"itemId":"L123"}}'
```

响应：

```json
{"messageId":"1758123456789-1","total":2,"success":2,"failed":0}
```

状态码：200 成功；400 请求体为空或 `bizModule` / `clientIds` 都为空；401 凭证不配对；403 模块不在白名单。

### Java 接入（nexus-client SDK）

```java
NexusRestClient client = NexusRestClient.builder()
        .wsBaseUrl("http://localhost:8089")
        .wsAppId("test")
        .wsApiKey("test_secret")
        .build();

PushResult result = client.wsPush(PushRequest.builder()
        .bizModule("test")
        .action("bid")
        .data(Map.of("itemId", "L123"))
        .build());
```

更多 SDK 用法见 [nexus-client/README.md](../nexus-client/README.md)。

---

## TCP 长连接接入通道（9091）

REST 推送是短连接，高频推送时每条消息都要重建 HTTP 连接、重走一次鉴权；TCP 通道让业务系统**一条长连接一直推**，还能靠心跳提前发现链路中断。

- **不做应用鉴权**：连接建立即可推送。TCP 端口与 REST 接口一样只对内网开放，「端口不暴露」就是它的边界。
- **帧格式**：`[4 字节大端长度][UTF-8 JSON]`。
- **报文体**：复用 `NexusMessage`，`event` 取 `TcpEvent`（`PUSH` / `RESULT` / `PING` / `PONG` / `ERROR`）。

业务系统侧不用自己写 Netty，`nexus-client` 里的 `NexusTcpClient` 已封装好：

```java
try (NexusTcpClient client = NexusTcpClient.builder()
        .host("10.0.0.8")
        .port(9091)
        .build()) {
    client.push(PushRequest.builder()
            .bizModule("order")
            .action("CREATE")
            .data(data)
            .build());
}
```

报文示例：

```json
{"event":"PUSH","data":{"bizModule":"test","action":"bid","data":{"itemId":"L123"}}}
```

回执示例：

```json
{"event":"RESULT","bizModule":"tcp","action":"result","ts":...,"data":{"messageId":"...","total":1,"success":1,"failed":0}}
```

规则：`bizModule` / `clientIds` 都为空等参数问题只回 `ERROR`、不关连接，改完报文可以接着推；心跳沿用 `nexus.ws.*`（写空闲 15s 服务端发 `PING`，读空闲 90s 未收到任何上行数据即判定失联并关闭）。

---

## 浏览器接入

```javascript
let clientId = '';
const ws = new WebSocket('ws://localhost:9090/ws?modules=test');

ws.onopen = () => {
  // 建连成功后必须重新拉取全量业务状态
  refreshAll();
};

ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.event === 'CONNECTED') {
    clientId = m.data.clientId;
    return;
  }
  if (m.event === 'PING') {
    ws.send(JSON.stringify({ event: 'PONG' }));
    return;
  }
  if (m.event === 'KICKED') {
    ws.close();
    return;
  }
  // 按 bizModule:action 路由
  const key = `${m.bizModule}:${m.action}:${m.data.itemId ?? ''}`;
  if (m.ts <= (lastTs[key] || 0)) return;
  lastTs[key] = m.ts;
  render(m);
};

ws.onclose = () => {
  // 浏览器需要自行实现重连
};
```

要点：

1. `clientId` 由服务端在握手时生成（UUID），随 `CONNECTED` 回执下发；前端不需要传任何标识。
2. **重连即换**：断开再连会拿到新 ID。要按用户维度稳定寻址，优先用**模块订阅**（`?modules=xxx`），或在业务系统侧维护「用户 → 当前 clientId」映射。
3. 浏览器 `WebSocket` 对象收不到协议层 ping/pong 帧，因此心跳必须是应用级消息：收到 `{"event":"PING"}` 必须回 `{"event":"PONG"}`，否则 90s 后被判定失联回收。
4. `ws.onopen` 必须重新拉取全量业务状态——服务端无快照、无补发。

---

## 管理界面

| 路径 | 说明 |
| --- | --- |
| `http://localhost:8089/admin` | 连接管理页：在线台账、模块分布、强制下线、推送应用管理 |
| `http://localhost:8089/console` | 推送测试页：建连、应答心跳、推送、看日志 |

运维接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/ws/admin/connections` | 在线连接概览 |
| DELETE | `/ws/admin/connections/{clientId}` | 强制下线 |
| GET | `/ws/admin/tcp/connections` | TCP 接入连接台账 |
| GET | `/ws/admin/apps` | 推送应用列表 |
| POST | `/ws/admin/apps` | 新增应用 |
| PUT | `/ws/admin/apps/{appId}` | 修改应用白名单 |
| DELETE | `/ws/admin/apps/{appId}` | 删除应用 |

> WebSocket 推送应用**仅存在内存中**，重启后回到默认应用 `test` / `test_secret`；生产环境请按需预先配置或自行实现持久化。
>
> 当前 `/ws/admin/**` 与 `/admin`、`/console` 页面**无 session 登录限制**，部署生产环境时务必限制内网访问或前置统一鉴权。

---

## 主要配置

`nexus-websocket/src/main/resources/application.properties`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8089` | HTTP / REST / 管理页端口 |
| `nexus.ws.ws-port` / `nexus.ws.ws-path` | `9090` / `/ws` | WebSocket 监听端口与握手路径 |
| `nexus.ws.tcp-port` | `9091` | TCP 接入端口（业务系统长连接） |
| `nexus.ws.heartbeat-interval` / `nexus.ws.heartbeat-timeout` | `15s` / `90s` | WS 心跳间隔与失联阈值 |
| `nexus.ws.max-connections` | `30000` | 最大 WS 连接数，0 = 不限 |
| `nexus.ws.max-frame-length` | `65536` | 单个 WebSocket 帧最大长度（字节） |
| `nexus.ws.boss-threads` / `nexus.ws.worker-threads` | `1` / `0` | Netty 线程数；0 = 取 Netty 默认值 |
| `nexus.ws.auth-enabled` | `true` | 推送鉴权（`X-Ws-AppId` + `X-Ws-Key`），**只作用于 REST 推送** |
| `nexus.ws.connect-auth-enabled` / `nexus.ws.connect-auth-token` | `false` / `U3RyZWFtTmV4dXM=` | 建连鉴权（查询参数 `token`），WebSocket 握手无法自定义请求头 |
| `nexus.ws.public-endpoint` | `` | 控制台页面拼接 WebSocket 地址时使用；留空则自动取当前 hostname + `nexus.ws.ws-port` |

---

## Docker 部署

`nexus-websocket/` 目录下已提供 `Dockerfile` 与 `docker-compose.yml`：

```bash
cd nexus-websocket

# 方式一：编排（推荐）
docker compose up -d --build

# 方式二：先打镜像再跑
./scripts/docker-build.sh       # Windows: scripts\docker-build.cmd
docker run -d --name nexus-websocket \
  -p 9090:9090 -p 8089:8089 -p 9091:9091 \
  simonking/nexus-websocket:1.0.0

docker compose logs -f
docker compose down
```

> 三个端口都需要暴露：9090（WebSocket）、8089（HTTP / 管理页 / REST 推送）、9091（TCP 推送通道）。

---

## 已知限制

| 限制 | 影响 | 演进方向 |
| --- | --- | --- |
| 单节点 | 连接表在内存，无法水平扩展 | 连接表外置 + 跨节点广播 |
| 无消息历史 | 断线期间消息丢失，需重连后拉全量 | 引入 Redis 后补发 |
| WebSocket 推送应用仅内存 | 重启后回到默认应用 | 实现持久化存储 |
| TCP 通道无应用鉴权 | 只依赖内网端口隔离 | 按业务需求追加令牌或 mTLS |
| ApiKey 明文 | 请求可重放 | HMAC 签名 + nonce |
| 同步扇出 | 慢消费者阻塞推送线程 | 每连接有界出站队列 |
| 运维接口暴露明文 apiKey | 需限制内网访问 | 对接统一登录 / 审计 |

明确非目标：消息必达、离线补推、跨实例路由、消息持久化、端到端加密。
