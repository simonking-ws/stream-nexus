package com.simonking.nexus.websocket;

import com.simonking.nexus.websocket.server.WebSocketNettyServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Netty 服务启动器
 *
 * <p>WebSocket 服务的 {@code bind().sync()} / {@code closeFuture().sync()} 都会阻塞当前线程，
 * 必须占一个独立线程；否则 {@code @PostConstruct} 卡住，Tomcat（HTTP 端口）根本起不来。
 *
 * <p>为什么不用 {@code @Bean(initMethod = "start")}：那是同步启动，阻塞后整个容器初始化就卡死了。
 *
 * @author simonking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NettyServerRunner {

    private final WebSocketNettyServer webSocketNettyServer;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("nexus-netty-starter");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void init() {
        executor.execute(() -> {
            log.info("websocket 服务初始化中 ...");
            try {
                webSocketNettyServer.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("websocket 服务启动中断", e);
            } catch (Exception e) {
                log.error("websocket 服务启动失败", e);
            }
        });
    }

    @PreDestroy
    public void destroy() {
        log.info("netty 服务关闭中 ...");
        webSocketNettyServer.shutdown();
        executor.shutdownNow();
    }
}
