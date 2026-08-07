package com.vca.telephony.provider.audiosocket;

/**
 * AudioSocket 接入参数。对应配置前缀 {@code vca.telephony.audiosocket.*}。
 *
 * @param port              监听端口。<b>Asterisk 是客户端</b>: dialplan 里 {@code AudioSocket(uuid,host:port)}
 *                          会主动连过来, 所以本进程要开 TCP 服务端
 * @param sampleRate        负载采样率(Hz)。AudioSocket 规范是 8000; 个别构建支持 16000
 * @param swapPayloadBytes  是否翻转音频负载字节序。本项目全链路按小端解析, 若联调时听到刺耳噪声
 *                          而不是人声, 打开它(见 {@link AudioSocketCodec})
 * @param acceptBacklog     TCP accept 队列长度
 */
public record AudioSocketConfig(
        int port,
        int sampleRate,
        boolean swapPayloadBytes,
        int acceptBacklog) {

    /** 默认: 9092 端口、8k、小端、backlog 128 */
    public static AudioSocketConfig defaults() {
        return new AudioSocketConfig(9092, 8000, false, 128);
    }

    /** 端口 0 = 由系统分配空闲端口(单测用) */
    public static AudioSocketConfig onPort(int port) {
        return new AudioSocketConfig(port, 8000, false, 128);
    }
}
