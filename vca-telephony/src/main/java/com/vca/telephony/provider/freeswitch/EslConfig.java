package com.vca.telephony.provider.freeswitch;

/**
 * 外呼用的 ESL 连接参数。对应 {@code vca.telephony.freeswitch.esl.*}。
 *
 * @param host             FreeSWITCH 地址
 * @param port             事件套接字端口(event_socket.conf 默认 8021)
 * @param password         event_socket.conf 里的密码
 * @param endpoint         拨号串模板, {@code {number}} 会被替换成被叫号码。接 SIP 中继是
 *                         {@code sofia/gateway/<网关名>/{number}}; 本地联调拨软电话是 {@code user/{number}}
 * @param context          接通后进入的拨号计划 context
 * @param exten            context 里的 extension —— 那里跑 socket 应用连回本进程
 * @param ringTimeoutMs    振铃多久没人接就放弃(ms)
 * @param answerWaitMs     从发起到媒体连进来的总等待上限(ms), 应大于 ringTimeoutMs
 * @param connectTimeoutMs TCP 连接与认证超时(ms)
 */
public record EslConfig(
        String host,
        int port,
        String password,
        String endpoint,
        String context,
        String exten,
        int ringTimeoutMs,
        int answerWaitMs,
        int connectTimeoutMs) {

    public EslConfig {
        if (endpoint == null || !endpoint.contains("{number}")) {
            throw new IllegalArgumentException("vca.telephony.freeswitch.esl.endpoint 必须包含 {number} 占位符, 当前: " + endpoint);
        }
    }

    public static EslConfig defaults() {
        return new EslConfig("127.0.0.1", 8021, "", "sofia/gateway/trunk/{number}",
                "ai-agent", "vca-outbound", 30_000, 45_000, 5_000);
    }
}
