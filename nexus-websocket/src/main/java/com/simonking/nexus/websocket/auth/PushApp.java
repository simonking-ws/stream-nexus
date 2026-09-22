package com.simonking.nexus.websocket.auth;

import java.util.List;

/**
 * 推送应用凭证
 *
 * <p>{@code allowedModules} 是应用级的模块白名单：即使推送鉴权通过，
 * 也只能推白名单内的业务模块，防止某个应用越权广播到别人的频道。
 *
 * @param appId          应用ID
 * @param apiKey         应用密钥
 * @param allowedModules 可用模块白名单，含 {@code *} 表示不限
 * @author simonking
 */
public record PushApp(String appId, String apiKey, List<String> allowedModules) {
}
