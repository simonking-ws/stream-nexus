package com.simonking.nexus.ws.client;

import com.simonking.nexus.ws.client.rest.NexusRestClient;
import com.simonking.stream.nexus.common.model.PushRequest;
import com.simonking.stream.nexus.common.model.PushResult;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST 推送的联调 demo（okhttp）
 *
 * <p>前置条件：先启动两个推送服务
 * <pre>
 *     mvn -pl nexus-sse spring-boot:run         // SSE 服务 8088
 *     mvn -pl nexus-websocket spring-boot:run   // WebSocket 服务 8089
 * </pre>
 * 再各自打开测试页建连接（都默认订阅 {@code test} 模块）：
 * SSE 是 <http://localhost:8088/console>，WebSocket 是 <http://localhost:8089/console>，
 * 然后直接跑本类的 {@code main}：每 2 秒往两个服务的 {@code test} 模块各推一条，
 * 控制台打印服务端回执（{@code total / success / failed}），两个页面都能看到落地。
 *
 * <p>顺手可以验证几件事：
 * <ol>
 *     <li>只起其中一个服务：另一路的 {@code push} 立即抛 {@code PushException}，互不干扰；</li>
 *     <li>把 {@code appId} 改成不存在的值：服务端返回 401，异常消息里带上状态码；</li>
 *     <li>关掉测试页再推：回执 {@code total} 变 0——REST 的价值就在这，推没推到人当场就知道。</li>
 * </ol>
 *
 * @author simonking
 */
public class RestPushDemo {

    public static void main(String[] args) throws Exception {
        NexusRestClient client = NexusRestClient.builder()
                .sseBaseUrl("http://127.0.0.1:8088")
                .wsBaseUrl("http://127.0.0.1:8089")
                // 两个服务各自内置的默认应用（生产环境换成各管理界面「推送应用」页签下发的）
                .sseAppId("test-demo").sseApiKey("c3RyZWFtLW5leHVz")
                .wsAppId("test").wsApiKey("test_secret")
                .build();

        final AtomicInteger seq = new AtomicInteger();
        Timer timer = new Timer("nexus-rest-demo", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                int no = seq.incrementAndGet();
                PushRequest request = PushRequest.builder()
                        .bizModule("test").action("DEMO").data("测试数据 " + no).build();
                try {
                    PushResult sse = client.ssePush(request);
                    PushResult ws = client.wsPush(request);
                    System.out.println("第 " + no + " 条已推送 | SSE: total=" + sse.getTotal()
                            + " success=" + sse.getSuccess() + " | WS: total=" + ws.getTotal()
                            + " success=" + ws.getSuccess());
                } catch (Exception e) {
                    System.out.println("第 " + no + " 条推送失败：" + e.getMessage());
                }
            }
        }, 0, 2000);

        System.out.println("推送中，按回车结束 ...");
        System.in.read();

        timer.cancel();
        client.close();
    }
}
