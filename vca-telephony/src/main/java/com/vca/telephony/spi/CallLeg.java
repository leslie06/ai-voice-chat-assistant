package com.vca.telephony.spi;

import reactor.core.publisher.Flux;

/**
 * 一路通话的媒体+信令抽象。实现方负责屏蔽 SIP/RTP 与编解码:
 * FreeSWITCH / Asterisk 已把 G.711(PCMA/PCMU) 解成裸 PCM, 所以本接口只谈 PCM。
 *
 * <p><b>本接口的实现必须是可替换的</b>: 单测用内存实现(不需要装 FreeSWITCH),
 * 生产用 {@code provider/freeswitch}(事件套接字 + unicast UDP), 备选 {@code provider/audiosocket}(Asterisk)。
 * 这与 domain 层 Provider SPI 的思路一致。
 *
 * <p>线程模型: {@link #writeAudio} 由单一节流线程调用, {@link #inboundAudio} 在网络线程上产出,
 * 实现方无需自行加锁, 但两者可能并发, 各自的内部状态要分开。
 */
public interface CallLeg {

    /** 通话唯一 id(用作 sessionId, 落库时能和 conversation_turn 对上) */
    String callId();

    /** 被叫号码; 呼入场景是主叫号码。传输层拿不到时为 null, 见 {@link #attachPeerNumber} */
    String peerNumber();

    /**
     * 这通电话打的是哪个号码。呼入时是我们的接入号 —— 多商家时据此区分"客户打给了哪一家";
     * 外呼时就是客户号码。传输层拿不到时为 null(AudioSocket 只有 UUID, 拿不到)。
     */
    default String calledNumber() {
        return null;
    }

    /**
     * 由外部把号码关联进来。
     *
     * <p>AudioSocket 这类"只传媒体"的通道拿不到号码 —— 它只有一个 UUID。号码握在发起呼叫的那一侧
     * (AMI/ARI), 呼叫接通后按 UUID 对上, 再回填进来。默认空实现: 本来就带号码的传输层不必理会。
     */
    default void attachPeerNumber(String number) {
        // 默认不支持回填; AudioSocket 等无号码通道覆写
    }

    /**
     * 媒体采样率(Hz)。电话网固定 8000(窄带); 少数高清语音线路是 16000。
     * {@link #inboundAudio} 与 {@link #writeAudio} 都以此速率的 16bit 小端单声道 PCM 为准。
     */
    default int sampleRate() {
        return 8000;
    }

    /** 上行音频流: 对端说话。实时到达, 不做缓冲。 */
    Flux<byte[]> inboundAudio();

    /**
     * 下行音频: 把一帧 PCM 送给对端。
     *
     * <p><b>必须按实时节奏调用</b>(每 {@code pacingMs} 一帧), 不能一次性灌完 ——
     * 这是电话与浏览器最本质的差异, 由 {@code PacingBuffer} 保证。
     */
    void writeAudio(byte[] pcm);

    /**
     * 机器人没话说的时候, 是否也要按节奏往线路写静音帧。
     *
     * <p>媒体服务器只在有帧写入时才发 RTP。不连续发的话, 客户说话、机器人听的那段时间线路上一个包都没有:
     * 对端抖动缓冲在机器人再开口时容易吞掉开头几个字, 部分运营商的会话边界控制器还会按"收不到 RTP"判媒体超时挂机。
     * 普通电话本来就是连续发包的, 所以需要的接入层返回 true, 由 {@code CallSession} 空闲时补静音帧。
     */
    default boolean needsContinuousMedia() {
        return false;
    }

    /** 信令事件流 */
    Flux<CallEvent> events();

    /** 主动挂机 */
    void hangup(String reason);
}
