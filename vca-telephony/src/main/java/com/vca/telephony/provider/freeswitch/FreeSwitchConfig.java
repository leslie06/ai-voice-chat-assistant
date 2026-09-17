package com.vca.telephony.provider.freeswitch;

/**
 * FreeSWITCH 接入参数(呼入与外呼共用的媒体侧)。对应 {@code vca.telephony.freeswitch.*}。
 *
 * <p>注意这里<b>没有</b> "FreeSWITCH 该绑哪个地址、怎么连回本进程"这类参数 —— 那是 FreeSWITCH 所在拓扑
 * 的事(容器里还是同机), 由它的拨号计划通过通道变量告诉我们, 见 {@link FreeSwitchCallLeg}。
 *
 * @param listenAddress      socket 服务端绑定地址。FreeSWITCH 拨号计划里的 {@code socket} 应用主动连过来。
 *                           默认只绑回环: 这个端口上没有任何鉴权, 能连上的人就能冒充 FreeSWITCH
 * @param port               socket 服务端端口(FreeSWITCH 惯例 8084)
 * @param sampleRate         线路采样率(Hz)。unicast 送来的是通道读编码的 L16, G.711 即 8000
 * @param mediaBindAddress   本进程 UDP 媒体口绑定地址。默认回环, 理由同上: 能往这个口发包就能往通话里灌音频
 * @param mediaWaitMs        下发 unicast 后等第一个媒体包的上限(ms)。等不到基本是拨号计划里的
 *                           {@code vca_media_remote_host} 配错了, 挂断并打出排查提示, 而不是让客户对着静音干等
 * @param handshakeTimeoutMs 建连后等 FreeSWITCH 应答握手命令的上限(ms)
 * @param acceptBacklog      TCP accept 队列长度
 */
public record FreeSwitchConfig(
        String listenAddress,
        int port,
        int sampleRate,
        String mediaBindAddress,
        int mediaWaitMs,
        int handshakeTimeoutMs,
        int acceptBacklog) {

    public static FreeSwitchConfig defaults() {
        return new FreeSwitchConfig("127.0.0.1", 8084, 8000, "127.0.0.1", 3000, 5000, 128);
    }

    /** 端口 0 = 由系统分配(单测用) */
    public static FreeSwitchConfig onPort(int port) {
        return new FreeSwitchConfig("127.0.0.1", port, 8000, "127.0.0.1", 3000, 3000, 16);
    }

    public FreeSwitchConfig withMediaWaitMs(int ms) {
        return new FreeSwitchConfig(listenAddress, port, sampleRate, mediaBindAddress, ms, handshakeTimeoutMs, acceptBacklog);
    }
}
