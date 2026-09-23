# Stream-Nexus SSE 推送系统 设计文档

> 版本：v1.0　|　技术栈：Spring Boot 4.1.1 / Java 17 / Spring MVC（`spring-boot-starter-webmvc`）
> 模块：`nexus-common`（契约）+ `nexus-sse`（实现，端口 8088）

---

## 1. 定位与设计约束

### 1.1 定位

`nexus-sse` 是一个**无状态扇出器（stateless fan-out）**：

- 只负责「连接管理 + 按业务模块路由 + 消息扇出」，**不存储业务状态、不存储消息历史**；
- 不依赖任何外部中间件（无 Redis / MQ / 数据库），全内存；
- 业务系统通过 HTTP 调用推送接口把消息灌进来，服务端不感知业务语义。

### 1.2 硬性约束（决定了后面所有取舍）

| 约束 | 含义 | 连带影响 |
| --- | --- | --- |
| 永不过期 | `new SseEmitter(0L)` + `async.request-timeout=0` | 容器永远不会替你超时，死连接必须自己发现、自己回收 |
| 无外部中间件 | 不能做全局 ID、不能做消息回放、不能做集群广播 | **只能单节点部署**；断线期间消息不补发 |
| 独立服务 | 与业务进程分离 | 只有两种寻址手段：按 `bizModule` 广播、按 `clientId` 定向，业务必须自己算好推给谁 |
| 消息体极简 | `SseMessage` 只有 6 个字段 | 无独立路由字段，`bizModule` 兼任路由键；同模块内多业务流靠 `data` 自带归属 ID 区分 |

---

## 2. 总体架构

```
┌──────────────┐  HTTP POST /sse/push               ┌─────────────────────────────┐
│  业务系统     │ ──(X-Sse-AppId / X-Sse-Key)───▶ │ PushAuthInterceptor  鉴权   │
│ (auction等)  │                                  │  └ 模块白名单 allowedModules│
└──────────────┘                                  │            ▼               │
                                                  │        SsePusher           │
┌──────────────┐  GET /sse/subscribe (SSE)        │   ├ IdGenerator.nextId()    │
│   浏览器      │ ◀───────── MESSAGE ────────────  │   └ SseSender → emitter    │
│ EventSource  │ ──POST /sse/pong ──────────────▶ │                            │
└──────────────┘                                  │  SseClientRegistry         │
                                                  │   ├ clients: id→SseClient  │
                                                  │   └ moduleIndex: module→ids│
                                                  │  HeartbeatTask (15s)       │
                                                  │   ├ 心跳下发               │
                                                  │   └ 回收（心跳超时/软重置）│
                                                  └─────────────────────────────┘
```

### 2.1 类职责

| 类 | 包 | 职责 |
| --- | --- | --- |
| `SseMessage` / `SseEvent` / `PushRequest` / `PushResult` | `common` | 跨模块契约，业务系统也依赖它 |
| `SseConstants` | `common.constant` | 协议层字符串常量（`SYS_MODULE` / `ACTION_*` / 鉴权头 / 通配符） |
| `IdGenerator` | `common.util` | 单调递增消息 ID（CAS 实现） |
| `SseProperties` | `sse.config` | 全部可调参数 |
| `SseClient` | `sse.connection` | 一条连接的运行时状态 |
| `SseClientRegistry` | `sse.connection` | 连接主表 + 业务模块倒排索引 + **统一回收** |
| `SseSender` / `SsePusher` | `sse.core` | 单条写入 / 按模块或 clientId 扇出 |
| `HeartbeatTask` | `sse.schedule` | 心跳 + 回收（永不过期下的主战场） |
| `PushAppRegistry` / `PushApp` | `sse.auth` | 推送应用运行时注册表：内置默认应用 `test` / `test_secret`，支持内存增删 |
| `PushAuthInterceptor` | `sse.auth` | 推送鉴权 + 业务模块白名单（读 `PushAppRegistry`） |
| `SseController` / `PushController` / `AdminController` | `sse.controller` | 订阅/心跳应答、推送、运维 |
| `PageController` | `sse.controller` | Thymeleaf 页面跳转：`/` → `/admin`（默认）、`/console` |
| `templates/admin.html` / `templates/console.html` | `sse.resources` | 连接管理页（在线台账 + 强制下线 + 应用管理，数据来自 `AdminController`）与推送测试页（应用下拉 / 内容自定义） |

---

## 3. 数据契约

### 3.1 `SseMessage<T>`（双向复用）

```java
{ "id": "1758...", "event": "MESSAGE", "bizModule": "lot", "action": "bid", "ts": 1758..., "data": {...} }
```

- `event` 是**协议层**枚举：`MESSAGE`（下行业务消息）/ `PING`（下行心跳）/ `PONG`（上行心跳应答）；
- `bizModule` + `action` 是**业务层**字符串，`bizModule` 同时兼任**路由键**（见 §3.2）；
- **没有独立路由字段**：`bizModule` 既出现在消息体里供客户端路由，也是服务端寻连接的依据。

> 设计取舍：把 `CONNECTED / HEARTBEAT / RECYCLE` 从枚举中删掉，改由 SSE 与 `EventSource` 原生机制承载——
> 建连 = `es.onopen`；断开重连 = `es.onerror` + 浏览器自动重连；服务端回收 = `emitter.complete()`。
> 例外：心跳（`PING` / `PONG`）**保留**在枚举里——它是唯一需要客户端显式响应的协议交互，
> 且用独立事件名下发，客户端 `addEventListener('PING')` 即可，不必在业务消息通道里靠 `bizModule=sse` 做特判。

### 3.2 寻址模型：`bizModule` 广播 + `clientId` 定向

连接 = `clientId` + 订阅的 `bizModule` 列表；推送只有两种寻址方式：

| 方式 | 订阅侧 | 推送侧 | 命中 |
| --- | --- | --- | --- |
| 按模块广播 | `/sse/subscribe?modules=lot,order` | `{"bizModule":"lot", ...}` | 所有订阅了 `lot` 的连接 |
| 按客户端定向 | —（只要在线即可，`clientId` 由服务端分配） | `{"clientIds":["C1"], ...}` | 指定 clientId 的连接 |
| 两者同时 | — | 两个字段都填 | **并集**，按 clientId 去重 |
| 全局订阅 | `/sse/subscribe`（`modules` 留空，默认 `*`） | 任意 `bizModule` 推送 | 每次按模块推送都会收到 |
| 全局广播 | — | `{"bizModule":"*", ...}` 或 `bizModule` 留空 | 全部在线连接 |

**`clientId` 由服务端分配**（UUID，见 `NexusUtils.newClientId()`），客户端建连时不传、也无从伪造，
只随建连回执（`sse/connected` 的 `data.clientId`）下发给客户端：

- 不让客户端自带 ID 是有意的：自带意味着客户端可以声明任意身份，服务端要么承担被冒用的风险，要么再叠一层令牌校验；
- 代价是它**随连接生命周期变化**（重连即换）：要按用户维度稳定寻址，优先用模块订阅，
  或在业务系统侧维护「用户 → 当前 clientId」映射（客户端每次建连后上报刷新）；
- 与 `nexus-websocket` 共用 `nexus-common` 的 `NexusUtils.newClientId()`，两个服务的客户端接入方式一致。

`PushRequest` 结构（`groups` 已删除）：

```java
public class PushRequest {
    private String bizModule;      // 按模块广播；留空按全局模块 * 处理（带 clientIds 时为纯定向）
    private List<String> clientIds; // 定向推送，可选
    private String action;
    private Object data;
}
```

**强制约定**：`bizModule` 是路由键，订阅侧与推送侧取值必须完全一致（含大小写），否则**静默推空**（`total=0`，不报错）。建议维护一份模块命名清单（如 `lot` / `order` / `user`）。

**全局模块 `*`**（`SseConstants.GLOBAL_MODULE`）：

- 订阅侧：`modules` 缺省或为空白时默认订阅它，避免客户端漏传参数后一条消息都收不到；
- 推送侧：以 `*` 为目标时广播给全部在线连接；以其它模块为目标时，订阅 `*` 的连接**额外命中**——即每次按模块推送都会带上全局订阅者；
- **`bizModule` 缺省（null / 空白）等价于显式写 `*`**：在 `PushController.normalizeBizModule` 里归一化，且归一化发生在白名单校验**之前**——否则受限应用只要留空模块就能广播全员，等于绕过鉴权；
- 例外：纯定向推送（只填 `clientIds`）不叠加全局模块，一对一消息不扩散给无关连接。同上理由，此时 `bizModule` 留空**不会**被归一化成 `*`，仍是定向。

注意：

- `*` 是业务可见的保留模块名，业务方不要再用它命名自己的业务模块，否则会与上述规则混淆；
- 白名单里也有一个 `*`（`SseConstants.MODULE_WILDCARD`），语义是「该应用不限可推模块」，与全局模块名同形但**命名空间不同**，两者各自判断、不可混用。

### 3.3 消息 ID 单调递增

```java
long next = Math.max(System.currentTimeMillis(), prev + 1);  // CAS
```

用途：客户端乱序丢弃与 `Last-Event-ID` 续传。**不做消息级确认**——心跳只证明连接存活，不代表某条消息已送达。
**必须是纯数字字符串**（SSE 的 `id:` 行不能含换行/非 ASCII 问题字符）。

---

## 4. 核心机制实现

### 4.1 建连

```49:87:nexus-sse/src/main/java/com/simonking/stream/nexus/sse/controller/SseController.java
    @GetMapping(path = "/sse/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam(defaultValue = SseConstants.GLOBAL_MODULE) String modules,
                                HttpServletRequest request) {
        if (properties.getMaxConnections() > 0 && registry.size() >= properties.getMaxConnections()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "connection limit reached");
        }
        ...
        String clientId = NexusUtils.newClientId();   // 服务端分配，客户端不传
        SseEmitter emitter = new SseEmitter(0L);
        SseClient client = new SseClient(clientId, emitter, moduleSet,
                IpUtils.resolve(request.getHeader(NexusConstants.HEADER_X_FORWARDED_FOR),
                        request.getHeader(NexusConstants.HEADER_X_REAL_IP), request.getRemoteAddr()));
        if (!registry.add(client)) {                    // UUID 撞号的理论兜底
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clientId already connected: " + clientId);
        }
        emitter.onCompletion(() -> registry.remove(clientId));
        emitter.onError(e -> registry.remove(clientId));
        ...
```

关键点：

- `clientId` 由**服务端生成**，随建连回执下发；客户端只带订阅模块，不再在 URL 里声明身份；
- `modules` 仍写进 query——`EventSource` 是 GET 且不能带自定义头，重连时浏览器原样复用 URL，
  订阅模块只能靠它保住；`clientId` 不进 URL，重连即换新 ID，也就没有「旧 ID 冲突」的窗口（见 E6）；
- 心跳应答 `/sse/pong?clientId=` 仍要带 ID：HTTP 应答与 SSE 长连接是两条独立连接，
  服务端只能靠这个 ID 定位该给哪条连接续命。

### 4.2 心跳与回收（永不过期下的核心）

```48:81:nexus-sse/src/main/java/com/simonking/stream/nexus/sse/schedule/HeartbeatTask.java
    @Scheduled(fixedDelayString = "${nexus.sse.heartbeat-interval:15000}")
    public void tick() {
        long now = System.currentTimeMillis();
        ...
        for (SseClient client : registry.all()) {
            if (heartbeatTimeout > 0 && now - client.getLastPongTime() > heartbeatTimeout) {
                registry.remove(client.getClientId());   // 判据1：连续多次 PING 无应答
                continue;
            }
            if (maxLifetime > 0 && now - client.getCreateTime() > maxLifetime) {
                registry.remove(client.getClientId());   // 判据2：软重置
                continue;
            }
            try {
                sender.send(client, SseMessage.builder()
                        .event(SseEvent.PING)             // 判据3：下发心跳，等客户端回 PONG
                        ...build());
            } catch (Exception e) {
                registry.remove(client.getClientId());   // 判据4：写入失败
            }
        }
    }
```

心跳采用**「问-答」（PING → PONG）**而非「客户端定时上报」：

| | 客户端定时上报（已废弃） | PING → PONG（当前） |
| --- | --- | --- |
| 触发时机 | 客户端自己起定时器 | 由服务端心跳驱动，天然对齐 |
| 与业务消息的耦合 | **强耦合**：`lastId` 只在收到业务消息后才有值，安静页面永远不上报 → 被误踢（见 E1） | **完全解耦**：有没有业务消息都照样应答 |
| 阈值设定 | `ack-timeout` 要与客户端自定的上报周期对齐，两边配置容易不一致 | `heartbeat-timeout` 直接按心跳间隔的倍数设定（≥3 倍） |
| 上行请求量 | 固定频率，与连接状态无关 | 与心跳同频（1 次 / 15s / 连接），且只在连接真的活着时才产生 |

三条回收判据 + 一条兜底：

| # | 判据 | 覆盖场景 | 兜底延迟 |
| --- | --- | --- | --- |
| 0 | `onCompletion` / `onError` 回调 | 正常关闭、网络断开（TCP FIN/RST） | 秒级 |
| 1 | `now - lastPongTime > 90s`（连续 6 次 PING 无 PONG） | **半开连接**（进程被杀、笔记本合盖、NAT 静默丢表） | ≤90s |
| 2 | `now - createTime > 60m` | 长期运行下的资源累积、内存碎片 | ≤60min |
| 3 | `send()` 抛异常 | 慢消费者、缓冲区写失败 | 立即 |
| 4 | 推送失败（`SsePusher` catch） | 推送时才发现死连接 | 立即 |

### 4.3 统一回收入口

```95:114:nexus-sse/src/main/java/com/simonking/stream/nexus/sse/connection/SseClientRegistry.java
    public void remove(String clientId) {
        SseClient client = clients.remove(clientId);
        if (client == null) {
            return;
        }
        for (String module : client.getModules()) {
            moduleIndex.computeIfPresent(module, (k, ids) -> {
                ids.remove(clientId);
                return ids.isEmpty() ? null : ids;
            });
        }
        try {
            client.getEmitter().complete();
        } catch (Exception ignored) {
        }
    }
```

### 4.4 推送与鉴权

- `POST /sse/push`，`PushAuthInterceptor` 拦 `/sse/push/**`，校验 `X-Sse-AppId`（应用标识）+ `X-Sse-Key`（应用密钥），二者必须配对匹配（`MessageDigest.isEqual` 常量时间比对防时序侧信道）；`X-Sse-AppId` 决定**应用**，`X-Sse-Key` 证明**身份**；
- 凭证不来自配置文件，而是运行时注册表 `PushAppRegistry`：内置默认应用 `test` / `test_secret`（白名单 `*`），其余应用在管理页「推送应用（appId / key）」页签增删改（内存态，重启回到默认应用）；删除后该 appId 的推送立即 401，白名单改动下一次推送即生效；
- 应用管理接口：`GET /sse/admin/apps`（返回明文 apiKey 与 `defaultAppId`，供测试页下拉使用）、`GET /sse/admin/apps/generate-key`（服务端生成 GUID 并做 Base64 编码，避免人工起弱口令）、`POST /sse/admin/apps`（apiKey 留空则自动生成；重名 → 409）、`PUT /sse/admin/apps/{appId}`（修改：只改传了的字段，apiKey 留空 / `allowedModules` 为 null 表示不改，`allowedModules` 空数组表示不限模块；appId 不存在 → 404）、`DELETE /sse/admin/apps/{appId}`；
- `PushController.checkModulePermission` 做**业务模块白名单**：白名单归属 appId，如新建的 `order-svc` 只允许 `order`，推其它模块返回 403；
- 目标解析见 `SsePusher.resolveTargets`：按模块命中 ∪ 定向命中，按 clientId 去重，一条连接不会被重复投递；
- `bizModule` 缺省 → 在校验前归一化为 `*` → 全模块广播（带 `clientIds` 时为纯定向）；请求体为空 → 400；
- 跨域由 `WebMvcConfig#addCorsMappings` 全局注册（`/**`，全量放行，无配置项）；`/sse/push` 的 `OPTIONS` 预检不带鉴权头，`PushAuthInterceptor` 对预检直接放行，避免浏览器侧跨域推送被 401 挡掉。

---

## 5. 配置清单

```properties
spring.mvc.async.request-timeout=0      # 与 new SseEmitter(0L) 成对出现
nexus.sse.heartbeat-interval=15s        # 压制 LB/NAT 空闲断链
nexus.sse.heartbeat-timeout=90s         # 失联判定：多久没收到 PONG 就回收（应 ≥ 3 倍 heartbeat-interval）
nexus.sse.max-lifetime=60m              # 软重置
nexus.sse.max-connections=30000         # 准入
nexus.sse.auth-enabled=true             # 推送鉴权（谁能推）
nexus.sse.connect-auth-enabled=false    # 建连鉴权（谁能连），默认关闭
nexus.sse.connect-auth-token=xxx        # 建连令牌，开启后必填；留空则一律拒绝
```

> 推送应用不在配置里：内置 `test` / `test_secret`，其余在管理页运行时增删（仅内存，重启回到默认应用）。

> 跨域：全局内置于 `WebMvcConfig#addCorsMappings`（`/**`，源/方法/头/凭证全量放行，无配置项）；
> `allowedOriginPatterns("*")` 是凭证模式下的唯一可行写法。

---

## 6. 客户端接入规范

1. 建连只带 `modules`：`/sse/subscribe?modules=lot,order`——`clientId` 由服务端分配，不需要客户端生成；
2. 从建连回执（`bizModule === 'sse' && action === 'connected'`）里取 `data.clientId` 并保存，**重连即换**，每次建连都要覆盖；
3. `es.onopen`：**必须重新拉取全量业务状态**（服务端无快照、无补发）；
4. 单 `MESSAGE` 监听器，按 `bizModule:action` 路由；`bizModule === 'sse'` 一律忽略（心跳/建连消息）；
5. 监听 `PING` 事件，收到后**立即** `POST /sse/pong?clientId=xxx`（用回执里那个 ID）；**不要自己起定时器上报**——心跳节奏由服务端驱动，漏答会在 `heartbeat-timeout`（默认 90s）后被回收；
6. `ts <= lastTs[key]` 的消息丢弃（防乱序）；
7. `es.onerror` 什么都不用做，浏览器自动重连；
8. 订阅的 `modules` 与业务系统推送的 `bizModule` 必须完全一致（含大小写），否则**静默推空**；定向推送所需的 `clientId` 由页面建连后上报给业务系统，由业务系统自行维护 `clientId ↔ userId` 映射（因重连会换 ID，每次建连后都要刷新该映射）。

---

## 7. 难点（为什么这么设计）

### 难点 1：「永不过期」下如何发现死连接

这是本方案最难的一点。`new SseEmitter(0L)` 意味着**容器不会替你超时**，而 TCP 有个致命特性：

> **半开连接（half-open）下，`send()` 依然返回成功。**

客户端进程被 kill、笔记本合盖、运营商 NAT 静默丢表时，操作系统不会发 FIN/RST。服务端 `send()` 只是把字节写进内核发送缓冲区，只要缓冲区没满就返回成功；对端永远收不到，也不会有 RST 回来（对端已不存在）。要等到发送缓冲区被填满才会阻塞/报错——在没有大流量时可能**几小时都不会发生**。

结论：**服务端单向探测在半开场景下无效，唯一可信的活性证明是「客户端主动说话」**。本方案把它固化成 PING/PONG 一问一答：服务端每 15s 问一次，客户端答一次，答不上来（连续 90s）即回收——这就是 `heartbeat-timeout=90s` 成为主回收判据的原因。

补充：`max-lifetime=60m` 不是为了解决半开，而是保证连接「有出有进」——长期运行下线程、缓冲区、GC 老年代都会累积，定期软重置让连接重新走一遍建连流程（顺带让客户端重新拉全量，自愈状态漂移）。

### 难点 2：心跳为什么不能带 `id`

SSE 协议规定浏览器会把收到的最后一条 `id:` 记下来，重连时通过 `Last-Event-ID` 请求头发回。

如果心跳也带 id，那么断线重连时浏览器上报的是**心跳 id** 而非业务消息 id，补发语义直接被污染（虽然本方案没实现补发，但协议语义必须保留，否则将来无法平滑升级）。

所以 `HeartbeatTask` 里**刻意不给心跳设置 id**，`SseSender` 也做了判空：

```32:38:nexus-sse/src/main/java/com/simonking/stream/nexus/sse/core/SseSender.java
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (message.getId() != null) {
            builder.id(message.getId());
        }
        builder.name(message.getEvent().name())
                .data(objectMapper.writeValueAsString(message), MediaType.APPLICATION_JSON);
        client.getEmitter().send(builder);
```

### 难点 3：`remove()` 的顺序不能反

`registry.remove()` 是**先删表、再 `complete()`**。这个顺序是有意为之：

`emitter.complete()` 会同步触发 `onCompletion` 回调，而回调里又调用了 `registry.remove(clientId)`。

- 先删表（当前实现）：回调进来时 `clients.remove()` 返回 `null`，直接 return，**安全**；
- 反过来（先 complete 再删表）：回调会在新连接已注册但旧连接还没删掉的窗口里执行，**可能把刚重连的新连接一起删掉**，造成客户端反复重连反复被踢。

这是一个典型的「回调重入 + 竞态」问题，改动 `remove()` 时必须保持这个顺序。

### 难点 4：`bizModule` 是粗粒度，同模块内多业务流怎么区分

按 `bizModule` 路由后，订阅了 `lot` 的连接会收到**所有拍品**的出价。消息体里没有更细的路由字段，客户端收到 `lot:bid` 时无法知道这是哪件拍品。

**解法（也是强制约定）：业务方必须在 `data` 里携带归属 ID**（`itemId` / `orderId`），客户端以 `data.itemId` 做二次路由。

代价换来了什么：消息体永远 6 个字段，业务方新增维度不需要改公共包、不需要改枚举。这是「极简消息体」的前提条件，**不是可选项**。

另一面：`bizModule` 因此不再是随意命名的标签——它同时是路由键，推送侧与订阅侧必须对齐（见 §3.2），命名错了会**静默推空**。

### 难点 5：无中间件 = 只能单节点

- 连接表在内存 → 多实例之间无法共享，A 实例上的连接收不到推给 B 实例的消息；
- `IdGenerator` 是进程内 `AtomicLong` → 多实例生成的 id 既不唯一也不单调，`Last-Event-ID` 续传与客户端乱序判据都会失效。

所以**当前实现必须单节点部署**。这是「不用 Redis/MQ」的直接代价，若将来要水平扩展，需要引入：连接表外置（Redis）+ 跨节点广播（Redis Pub/Sub 或 HTTP peer 转发）+ 全局 ID（Redis INCR 或雪花带 workerId）。

### 难点 6：断线期间消息一定丢

SSE 自带 `Last-Event-ID` 补发能力，但补发需要服务端缓存消息历史——**无中间件就没有历史**。

因此明确约定：**`es.onopen` 必须重新拉全量**。系统只保证「在线期间的增量实时」，不保证「离线期间的增量不丢」。业务侧要能接受「重连即全量刷新」。

---

## 8. 易错点清单

> 按危险程度排序。★ = 当前代码中已存在的隐患，建议尽快处理。

### ✅ E1（已解决）：安静页面不上报 ACK → 90s 后被误踢

**原问题**：ACK 模式下 `lastId` 只在收到业务消息后才赋值，建连消息与心跳都在路由前 `return`，于是**无业务消息的页面永远不上报**，90s 后被回收 → `onerror` 重连 → 又被踢，形成周期性掉线。

**现状**：改为 PING → PONG 后，应答由服务端心跳驱动，与业务消息完全解耦，该隐患不复存在。

**遗留风险（升级时必看）**：客户端必须真的监听 `PING` 并回 `PONG`。若接入方仍用旧的「定时上报 ACK」SDK，不发 PONG，连接会在 `heartbeat-timeout` 后被静默回收——**协议变更必须前后端同步升级**。

### ★ E2：乱序丢弃的 key 粒度太粗，不同拍品的消息会互相吞掉

```88:90:nexus-sse/src/main/resources/static/index.html
        const key = `${m.bizModule}:${m.action}`;
        if (m.ts <= (lastTs[key] || 0)) return;   // 丢弃乱序 / 旧消息
        lastTs[key] = m.ts;
```

订阅 `lot` 模块后，L1 与 L2 的出价都走 `lot:bid`，共用同一个 key。若 L1 收到 ts=1000 的消息，随后 L2 的 ts=999 消息会被**误判为乱序而丢弃**——但它是 L2 的最新消息，丢了就是真丢。

修复：key 必须带上归属 ID，`${m.bizModule}:${m.action}:${m.data.itemId ?? ''}`。这也再次印证 E4（data 必须带归属 ID）不是建议而是必需。

### ★ E3：`/sse/subscribe` 与 `/sse/pong` 完全无鉴权

`PushAuthInterceptor` 只拦 `/sse/push/**`。任何人可以：

- 订阅任意 `modules=order,user` → **收到该模块的全部消息**，其中包含他人订单、账户等私密数据；
- 伪造 `clientId` 调 `/sse/pong` → 给不存在的连接保活（无害），或撞库保活（绕过回收）。
  ID 改为服务端分配的 UUID 后，枚举撞号已不现实，但**拿到真实 ID 的人仍可替该连接保活**（ID 随建连回执明文下发）。

修复：敏感模块（如 `user` / `order`）的订阅必须带**订阅票据**（JWT，`jti` = clientId，claim 里声明允许订阅的模块），服务端校验票据与 `modules` 的交集，敏感模块无票据一律拒绝。PONG 也需轻量校验（至少校验 clientId 存在且格式合法）。

### ★ E4：`data` 不带归属 ID → 客户端无法路由

见「难点 4」。`PushRequest.data` 的 javadoc 已写，但**服务端无法校验**，属于纯约定。接入文档里要加粗，Code Review 时重点看。

### ★ E5：推送鉴权是 ApiKey 明文，可重放

当前是 `X-Sse-AppId` + `X-Sse-Key` 明文比对，**没有时间戳、没有 nonce、不签名 body**：

- 密钥在网关/代理/Nginx 访问日志里可能落盘；
- 请求被抓到即可无限重放。

升级路径（接口位置不变）：以 apiKey 为密钥做 HMAC-SHA256 签名 `appId + timestamp + nonce + sha256(body)`（密钥本身不出报文，appId 明文随 `X-Sse-AppId` 传递用于定位应用），服务端校验 5 分钟时间窗 + nonce 去重（`PushAuthInterceptor` 注释里已预留）。

### E6：重连 409 窗口（已随「服务端分配 ID」消解）

原问题：clientId 由客户端生成并写进 URL，浏览器自动重连时复用了同一个 ID；旧连接若还没被回收（半开连接，`onCompletion` 未触发），`registry.add()` 返回 false → **409** → 重连失败 → 3s 后重试，可能持续到旧连接心跳超时（最长 90s）。

现状：clientId 改为服务端每次建连生成 UUID 后，**重连必然拿到新 ID，不会与旧连接撞号**，该窗口随之消失；`registry.add()` 的冲突分支退化为 UUID 撞号的理论兜底。

代价（需业务侧知悉）：ID 不再稳定，业务系统侧「用户 → clientId」的映射必须在每次建连后刷新，否则定向推送会打到已失效的连接上（`total` 里命中不到，静默丢消息）。要稳定寻址请用模块订阅。

### E7：`max-lifetime` 重连风暴

服务重启后所有连接几乎同时建立，60 分钟后**同时触发软重置**，瞬间几万连接一起重连 + 一起拉全量，可能打爆业务接口。

缓解：给软重置加随机抖动（`maxLifetime * (0.8 ~ 1.0)`），或在 `SseClient` 上记录一个随机的 `plannedRecycleAt`。

### E8：`spring.mvc.async.request-timeout` 与 `SseEmitter(0L)` 必须成对配置

只写 `new SseEmitter(0L)` 不生效（容器层仍会超时），只写配置文件而不改 emitter 也不生效。本项目同时配置了三处：`application.properties` 的 `spring.mvc.async.request-timeout=0`、`WebMvcConfig.configureAsyncSupport.setDefaultTimeout(0)`、以及 `new SseEmitter(0L)`。**改动其中任何一处都要确认另外两处**。

### E9：模块索引泄漏

任何旁路删除（直接 `clients.remove()`、`emitter.complete()` 而不走 registry）都会让 `moduleIndex` 里残留死 clientId，导致：按模块推送命中不存在的连接（`total` 虚高、`failed` 上升）、内存缓慢泄漏。

规范：**所有回收路径必须且只能调用 `SseClientRegistry.remove()`**。

### E10：`PushResult.success` 的语义是「写入成功」而非「客户端收到」

`emitter.send()` 成功只代表数据进了内核缓冲区（见难点 1）。心跳模式下**连批量确认都没有了**——PONG 只证明连接活着，与消息无关，所以服务端**完全无法知道某条消息是否送达、是否被处理**。对外暴露指标时不要把 `success` 当送达率宣传；若业务需要送达证明，必须在业务层另做（客户端收到后回调业务接口）。

### E11：Jackson 3 包名

Spring Boot 4 使用 Jackson 3，`SseSender` 注入的是 `tools.jackson.databind.ObjectMapper`（不是 `com.fasterxml.jackson`）。**写成 `com.fasterxml.jackson.databind.ObjectMapper` 会导致注入失败或序列化行为不一致**（例如 `SseMessage` 上 `@JsonInclude` 注解若引错包会静默失效）。

### E12：反向代理会吃掉 SSE

Nginx 默认开缓冲，且 `proxy_read_timeout` 默认 60s：

```nginx
proxy_buffering off;              # 否则消息攒在缓冲区里不下发
proxy_read_timeout 3600s;         # 必须大于心跳间隔，否则被代理掐断
proxy_set_header Connection '';
chunked_transfer_encoding on;
```

另外浏览器对同一域名有 **HTTP/1.1 6 连接上限**，多标签页会把连接数吃满（这也是为什么每个页面最好只开一条 SSE 连接，靠订阅多个 `bizModule` 复用）。

### E13：`registry.all()` 每次全量拷贝

```54:54:nexus-sse/src/main/java/com/simonking/stream/nexus/sse/schedule/HeartbeatTask.java
        for (SseClient client : registry.all()) {
```

`all()` 内部 `new ArrayList<>(clients.values())`，3 万连接时每 15s 拷贝一次集合。当前量级可接受，量大了要改成无拷贝遍历（`ConcurrentHashMap.forEach`）或分段扫描。

### E14：慢消费者会阻塞推送线程

`SseEmitter.send()` 是**同步阻塞写**。某个客户端网络很慢时，内核缓冲区写满后 `send()` 会阻塞，而当前 `SsePusher` 是在 HTTP 请求线程里同步扇出的——**一个慢客户端能拖死整次推送**。

后续可选：每条连接配一个有界出站队列（容量 256，溢出 `DROP_OLDEST`），推送线程只入队，由独立线程池异步写；队列满即判定为慢消费者并回收。

### E15：`AdminController` 无鉴权

`/sse/admin/connections` 暴露全部 clientId、订阅模块、`lastPongTime`，`/sse/admin/connections/{id}` 可强制下线任意连接。**必须加鉴权并限制内网访问**。

新增的 `/admin` 管理页（Thymeleaf 模板 `templates/admin.html`）把这套接口直接暴露成了可视化页面（可一键下线任意连接），风险面比裸接口更大——部署时该页面**必须**随接口一起做鉴权 / 内网隔离，不能随服务一起裸露在公网。

更严重的是 `/sse/admin/apps`：它返回**明文 apiKey**（测试页要用它拼鉴权头），拿到该接口等于拿到全部推送权限；`POST` 还能凭空新增应用、`DELETE` 可让任一应用立即失效。这是目前风险最高的一组接口，必须与 `/sse/push` 同等级保护，而不是「反正只是运维接口」。

### E16：ID 单调性在重启/多实例下会破

`IdGenerator` 基于进程内 `AtomicLong`：
- 重启后 `LAST` 重置为 `currentTimeMillis`，若停机时间极短可能生成比停机前更小的 id（客户端 `ts` 乱序判据可兜底）；
- 多实例完全失效（见难点 5）。

---

## 9. 已知限制与后续演进

| 限制 | 影响 | 演进方向 |
| --- | --- | --- |
| 单节点 | 无法水平扩展 | 连接表外置 + 跨节点广播 |
| 无消息历史 | 断线消息丢失 | 引入 Redis 后按 `Last-Event-ID` 补发 |
| ApiKey 明文 | 可重放 | HMAC + nonce |
| 订阅/心跳应答无鉴权 | 越权订阅敏感业务模块 | JWT 订阅票据 |
| 同步扇出 | 慢消费者阻塞 | 每连接有界出站队列 |
| 无消息合并 | 高峰期刷屏 | 同 `bizModule:action` 覆盖合并（约 15 行） |
| 定向推送无绑定校验 | 拿到一对 appId+key 即可向任意 clientId 投消息 | clientId 与用户归属绑定校验 |
| 重连间隔不可控 | 3s 固定 | 下发 SSE `retry:` 字段 |

**明确的非目标**（设计上主动放弃，勿再引入）：消息必达、离线补推、跨实例路由、消息持久化、端到端加密。

---

## 10. 冒烟验证

1. 启动 `NexusSseApplication`（8088），访问 `http://localhost:8088/` → 自动跳转 `/admin`（默认管理页），点导航进 `/console` 推送测试页；
2. 点「连接」→ 状态变「已连接」，日志出现建连消息（`sse/connected`）；
3. 推送应用下拉默认选中内置应用 `test`（key `test_secret` 自动带出），业务模块默认 `test`（订阅默认值也是 `test`），推送内容可直接在 JSON 里改，点「推送」→ 日志出现 `test:bid` 及自定义字段，返回 `{"total":1,"success":1,"failed":0}`；把模块改成未订阅的 `order` 再推 → `total=0`（静默推空，是预期行为）；
4. `curl http://localhost:8088/sse/admin/connections` 查看连接与模块统计；定向验证：把页面显示的 clientId 填进「定向 clientId」后再推送 → 只有该连接收到；
5. **心跳验证**：打开页面后观察日志每 15s 收到一次 `PING` 并回 `PONG`（Network 面板可见 `/sse/pong`）；**回收验证**：直接断网，观察 90s 内服务端日志出现 `recycle by heartbeat timeout`；
6. **鉴权验证**：不带 `X-Sse-Key` 或 `X-Sse-AppId` 未登记，请求 `/sse/push` 应返回 401（两者不配对也 401）；在管理页新增一个只允许 `order` 的应用，用它推 `bizModule=test` 应返回 403，用它**留空 `bizModule`** 同样应返回 403（不允许靠留空绕过白名单）；无白名单限制的应用两个寻址字段都不填应全模块广播（`total` = 在线连接数）。
