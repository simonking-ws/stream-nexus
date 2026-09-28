package com.simonking.nexus.websocket.config;

import com.simonking.stream.nexus.common.location.IpLocationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * IP 归属地（城市）装配
 *
 * <p>实现类在 {@code nexus-common}（SSE 与 WebSocket 共用），这里只负责按本服务的配置项
 * {@code nexus.ws.ip2region-path} 把它装配成 Bean——公共库不反过来认识任何一方的配置前缀，
 * 所以由各服务自己 @Bean，而不是给它挂 {@code @Component}（本包也不在 common 的扫描路径下）。
 *
 * @author simonking
 */
@Configuration
public class IpLocationConfig {

    @Bean
    public IpLocationService ipLocationService(WsProperties properties, ResourceLoader loader) {
        return new IpLocationService(properties.getIp2regionPath(), loader);
    }
}
