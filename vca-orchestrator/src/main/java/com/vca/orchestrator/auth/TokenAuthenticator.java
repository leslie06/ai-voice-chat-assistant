package com.vca.orchestrator.auth;

/**
 * 登录令牌校验端口。让接入层(WebSocket 等)能验证用户登录令牌而无需依赖具体的账号/存储实现 ——
 * 与 {@link com.vca.orchestrator.recorder.ConversationRecorder} 同为旁路 SPI, 由 {@code vca-store} 实现。
 *
 * <p>未注入实现(账号系统未启用)时, 接入层回退到原有的共享 token 鉴权。
 */
@FunctionalInterface
public interface TokenAuthenticator {

    /** 校验令牌, 通过返回用户标识(如 userId 字符串), 否则返回 {@code null}。 */
    String authenticate(String token);
}
