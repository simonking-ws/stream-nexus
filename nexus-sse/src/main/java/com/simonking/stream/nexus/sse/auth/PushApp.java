package com.simonking.stream.nexus.sse.auth;

import java.util.List;

/**
 * 推送应用凭证：{@code appId} 决定身份与模块白名单，{@code apiKey} 证明身份
 *
 * @param appId          应用标识，对应请求头 {@code X-Sse-AppId}
 * @param apiKey         应用密钥，对应请求头 {@code X-Sse-Key}
 * @param allowedModules 允许推送的业务模块，含 {@code *} 表示不限制
 */
public record PushApp(String appId, String apiKey, List<String> allowedModules) {
}
