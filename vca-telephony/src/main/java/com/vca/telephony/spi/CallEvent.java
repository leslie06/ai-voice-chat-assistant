package com.vca.telephony.spi;

/**
 * 一路通话的信令事件。由 {@link CallLeg#events()} 吐出, 供 {@code CallSession} 驱动通话状态机。
 *
 * <p>与浏览器接入的区别: 浏览器只有"连上/断开"两态, 电话有一串中间态, 且<b>接通与否直接决定要不要花钱</b>
 * (彩铃期间就开始跑 ASR/LLM/TTS 是纯浪费), 所以信令必须显式建模。
 *
 * @param type   事件类型
 * @param detail 附加信息: DTMF 是按键字符, HANGUP 是挂机原因, 其余可为 null
 */
public record CallEvent(Type type, String detail) {

    public enum Type {
        /** 对端振铃中 */
        RINGING,
        /**
         * 早期媒体(SIP 183): 通道里已有声音, 但<b>人还没接</b> —— 彩铃、运营商提示音("已关机"/"暂时无法接通")
         * 都走这里。除非要做音频特征 CPA, 否则这段音频应当丢弃, 不喂 VAD/ASR。
         */
        EARLY_MEDIA,
        /** 真接通(SIP 200 OK): 从这一刻起才开始对话与计费 */
        ANSWERED,
        /** 用户按键 */
        DTMF,
        /** 通话结束(任一方挂机 / 线路异常 / 超时) */
        HANGUP
    }

    public static CallEvent of(Type type) {
        return new CallEvent(type, null);
    }

    public static CallEvent dtmf(String digit) {
        return new CallEvent(Type.DTMF, digit);
    }

    public static CallEvent hangup(String reason) {
        return new CallEvent(Type.HANGUP, reason);
    }
}
