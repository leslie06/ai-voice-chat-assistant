package com.vca.telephony.provider.ami;

/**
 * AMI 连接与外呼参数。对应 {@code vca.telephony.ami.*}。
 *
 * @param host         Asterisk 地址
 * @param port         AMI 端口(manager.conf 默认 5038)
 * @param username     manager.conf 里的账号
 * @param secret       密码
 * @param trunk        SIP 中继名(PJSIP endpoint), 拨号串拼成 {@code PJSIP/<号码>@<trunk>}
 * @param context      接通后进入的 dialplan context —— 那里跑 AudioSocket
 * @param exten        context 内的 extension
 * @param ringTimeoutMs 振铃多久没人接就放弃(ms)
 * @param answerWaitMs 从发起到媒体连进来的总等待上限(ms), 应大于 {@code ringTimeoutMs}
 * @param connectTimeoutMs TCP 连接与登录超时(ms)
 */
public record AmiConfig(
        String host,
        int port,
        String username,
        String secret,
        String trunk,
        String context,
        String exten,
        int ringTimeoutMs,
        int answerWaitMs,
        int connectTimeoutMs) {

    public static AmiConfig defaults() {
        return new AmiConfig("127.0.0.1", 5038, "", "", "trunk",
                "ai-agent", "s", 30_000, 45_000, 5_000);
    }
}
