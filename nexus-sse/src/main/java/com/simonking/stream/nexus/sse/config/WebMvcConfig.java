package com.simonking.stream.nexus.sse.config;

import com.simonking.stream.nexus.sse.auth.ConnectAuthInterceptor;
import com.simonking.stream.nexus.sse.auth.PushAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 *
 * @author simonking
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final PushAuthInterceptor pushAuthInterceptor;

    private final ConnectAuthInterceptor connectAuthInterceptor;

    /**
     * 全局跨域：作用于 {@code /**} 全部路径（SSE 订阅、推送、运维接口、静态测试页），全量放行
     *
     * <p>本服务是**内网推送通道**，跨域只做「让浏览器能连上」这一件事，不承载鉴权职责
     * （真正的鉴权在 {@link PushAuthInterceptor}），因此不引入配置项：
     * 源、方法、头、凭证全部放开，避免多一套配置就多一处漏配。
     *
     * <p>用 {@code allowedOriginPatterns("*")} 而非 {@code allowedOrigins("*")}：后者在
     * {@code allowCredentials(true)} 下不接受 {@code *}，配置校验直接失败。
     *
     * <p>自定义头（{@code X-Sse-AppId} / {@code X-Sse-Key}）必须放开，
     * 否则 {@code /sse/push} 的预检会被浏览器判失败。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 两条鉴权链各管一段：{@code /sse/push/**} 验应用凭证（谁能推），
     * {@code /sse/subscribe} 验建连令牌（谁能连）；后者默认关闭，见 {@link ConnectAuthInterceptor}
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(pushAuthInterceptor).addPathPatterns("/sse/push/**");
        registry.addInterceptor(connectAuthInterceptor).addPathPatterns("/sse/subscribe");
    }

    /**
     * 0 = 异步请求不过期，SSE 长连接由 {@code HeartbeatTask} 自行回收
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(0L);
    }
}
