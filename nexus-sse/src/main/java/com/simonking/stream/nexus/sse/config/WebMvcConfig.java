package com.simonking.stream.nexus.sse.config;

import com.simonking.stream.nexus.sse.auth.PushAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(pushAuthInterceptor).addPathPatterns("/sse/push/**");
    }

    /**
     * 0 = 异步请求不过期，SSE 长连接由 {@code HeartbeatTask} 自行回收
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(0L);
    }
}
