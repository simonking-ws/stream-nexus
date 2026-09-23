package com.simonking.nexus.websocket;

import com.simonking.nexus.websocket.server.TcpNettyServer;
import com.simonking.nexus.websocket.server.WebSocketNettyServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty 服务启动器
 *
 * <p>两个 Netty 服务（WebSocket 9090 / TCP 9091）的 {@code bind().sync()} / {@code closeFuture().sync()}
 * 都会阻塞当前线程，各自必须占一个独立线程；否则 {@code @PostConstruct} 卡住，Tomcat（HTTP 端口）根本起不来。
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

    private final TcpNettyServer tcpNettyServer;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("nexus-netty-starter-" + THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void init() {
        submit("websocket", webSocketNettyServer::start);
        submit("tcp", tcpNettyServer::start);
    }

    @PreDestroy
    public void destroy() {
        log.info("netty 服务关闭中 ...");
        webSocketNettyServer.shutdown();
        tcpNettyServer.shutdown();
        executor.shutdownNow();
    }

    private void submit(String name, ServerStarter starter) {
        executor.execute(() -> {
            log.info("{} 服务初始化中 ...", name);
            try {
                starter.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("{} 服务启动中断", name, e);
            } catch (Exception e) {
                log.error("{} 服务启动失败", name, e);
            }
        });
    }

    /**
     * 启动动作：与 {@code Thread::run} 同形，允许抛出受检异常
     */
    private interface ServerStarter {
        void start() throws InterruptedException;
    }
}
