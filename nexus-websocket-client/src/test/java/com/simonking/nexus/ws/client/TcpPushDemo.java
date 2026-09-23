package com.simonking.nexus.ws.client;

import com.simonking.nexus.ws.client.tcp.NexusTcpClient;
import com.simonking.stream.nexus.common.model.PushRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 长连接推送的联调 demo
 *
 * <p>前置条件：先启动 {@code nexus-websocket}（{@code mvn -pl nexus-websocket spring-boot:run}），
 * 再打开 <http://localhost:8089/console> 点「连接」（默认订阅 {@code test} 模块），
 * 然后直接跑本类的 {@code main}：每 2 秒往 {@code test} 模块推一条，页面日志即可看到落地。
 *
 * <p>顺手可以验证两件事：
 * <ol>
 *     <li>把推送服务停掉再启动，观察 {@code nexus-tcp-reconnect} 线程自动重连；</li>
 *     <li>打开 <http://localhost:8089/admin> 的「TCP 接入」页签，看这条连接的台账。</li>
 * </ol>
 *
 * @author simonking
 */
public class TcpPushDemo {

    public static void main(String[] args) throws Exception {
        NexusTcpClient client = NexusTcpClient.builder()
                .host("127.0.0.1")
                .port(9091)
                .build();

        // 可选：先连上，让「服务没起 / 地址填错」立刻暴露，而不是等到第一次推送
        client.connect();

        final AtomicInteger seq = new AtomicInteger();
        Timer timer = new Timer("nexus-tcp-demo", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                int no = seq.incrementAndGet();
                try {
                    client.push(PushRequest.builder().bizModule("test").action("DEMO").data("测试数据").build());
                    System.out.println("第 " + no + " 条已发出");
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
