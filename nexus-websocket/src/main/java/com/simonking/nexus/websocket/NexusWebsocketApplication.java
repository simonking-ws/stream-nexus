package com.simonking.nexus.websocket;

import com.simonking.nexus.websocket.config.WsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 独立部署的 WebSocket 推送服务
 *
 * <p>一个进程两组端口：
 * <ul>
 *     <li>{@code 8089}：HTTP，REST 推送接口（{@code POST /ws/push}）+ 管理界面 + 推送测试页 + 运维接口；</li>
 *     <li>{@code 9090}：Netty WebSocket，终端建长连接，只负责收推送。</li>
 * </ul>
 *
 * @author simonking
 */
@SpringBootApplication
@EnableConfigurationProperties(WsProperties.class)
public class NexusWebsocketApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusWebsocketApplication.class, args);
    }

}
