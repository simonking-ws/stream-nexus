package com.simonking.nexus.ws.client;

import com.simonking.nexus.ws.client.model.PushResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 本地联调示例（非单元测试：直接跑 main）
 *
 * <p>前置条件：先启动 nexus-websocket 服务（HTTP 端口 8089、应用 test / test_secret）。
 * 运行后每 2 秒推送一条消息，可在管理界面（http://localhost:8089/admin）看到在线连接，
 * 在推送测试页（http://localhost:8089/console）看到消息落地。
 *
 * @author simonking
 */
public class PushDemo {

    public static void main(String[] args) throws Exception {
        NexusWsClient client = NexusWsClient.builder()
                .host("127.0.0.1")
                .port(8089)
                .appId("test")
                .apiKey("test_secret")
                .buildClient();

        Timer timer = new Timer("push-demo", true);
        timer.schedule(new TimerTask() {
            private int seq = 0;

            @Override
            public void run() {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("seq", ++seq);
                data.put("text", "来自业务系统的推送 " + seq);
                data.put("ts", System.currentTimeMillis());
                try {
                    PushResult result = client.push("test", "test_msg", data);
                    System.out.println("推送结果：" + result);
                } catch (Exception e) {
                    System.err.println("推送失败：" + e.getMessage());
                }
            }
        }, 0, 2000);

        System.out.println("推送中，按回车退出 ...");
        System.in.read();

        timer.cancel();
        client.close();
    }
}
