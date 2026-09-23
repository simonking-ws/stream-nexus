package com.simonking.nexus.websocket;

import com.simonking.nexus.websocket.config.WsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 独立部署的 WebSocket 推送服务
 *
 * <p>一个进程三组端口：
 * <ul>
 *     <li>{@code 8089}：HTTP，REST 推送接口（{@code POST /ws/push}）+ 管理界面 + 推送测试页 + 运维接口；</li>
 *     <li>{@code 9090}：Netty WebSocket，终端建长连接，只负责收推送；</li>
 *     <li>{@code 9091}：Netty TCP，业务系统建长连接，把待推消息交进来后由本服务扇出到终端。</li>
 * </ul>
 *
 * <p>后两者共享同一张终端连接注册表，因此 TCP 收到消息后可以直接写终端的 Channel，
 * 不需要任何跨进程转发——这是「WebSocket 服务独立部署、业务系统只引客户端 SDK」能成立的前提。
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
