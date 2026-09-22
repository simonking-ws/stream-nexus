package com.simonking.nexus.websocket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 *
 * <p>只做一件事：放开跨域。管理界面与测试页可能与推送接口不同源（例如把页面挂到别的域名下调试），
 * 而本服务是**内网推送通道**，跨域不承担鉴权职责——真正的鉴权在推送接口里校验 appId / apiKey。
 *
 * @author simonking
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
