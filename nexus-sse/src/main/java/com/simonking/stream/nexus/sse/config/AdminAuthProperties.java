package com.simonking.stream.nexus.sse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理页登录配置
 *
 * <p>与 {@link SseProperties} 里的两套鉴权是**三件不同的事**，别混：
 * <ul>
 *   <li>{@code authEnabled}（{@link SseProperties#isAuthEnabled()}）：管「谁能推」，验 appId / apiKey；</li>
 *   <li>{@code connectAuthEnabled}：管「谁能连」，验建连令牌；</li>
 *   <li>本配置：管「谁能打开管理页、调运维接口」，验账号密码。</li>
 * </ul>
 *
 * <p>默认账号 {@code admin / adminsse}：单账号、明文口令、仅内存态 session，
 * 定位是「内网运维页不裸奔」，不是完整账号体系——部署时仍应改口令并限制内网访问
 * （见文档 E15）。
 *
 * @author simonking
 */
@Data
@ConfigurationProperties(prefix = "nexus.sse.admin")
public class AdminAuthProperties {

    /**
     * 是否开启管理页登录
     *
     * <p>{@code false} 时 {@code /admin}、{@code /console} 与 {@code /sse/admin/**} 全部放行
     * （退回加登录之前的行为），仅限纯内网自用且能接受裸奔的场景。
     */
    private boolean authEnabled = true;

    /**
     * 登录账号
     */
    private String username = "admin";

    /**
     * 登录密码
     */
    private String password = "adminsse";
}
