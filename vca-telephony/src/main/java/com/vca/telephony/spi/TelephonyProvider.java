package com.vca.telephony.spi;

import reactor.core.publisher.Mono;

/**
 * 外呼能力端口: 发起一通电话, 拿到一路 {@link CallLeg}。
 *
 * <p>Phase 0(本地闭环)用不到本接口 —— 那时是软电话拨进来, 由媒体接入层直接建 {@code CallLeg}。
 * Phase 1 接 SIP 中继后, 由 FreeSWITCH ESL 实现 originate。此处先把契约定下来, 让上层任务调度
 * 不必等媒体接入完成即可编写。
 */
public interface TelephonyProvider {

    /**
     * 发起外呼。
     *
     * @param callee   被叫号码
     * @param callerId 主叫号显(线路侧可能覆盖)
     * @return 通话建立后的一路 leg; 呼叫失败(空号/关机/拒接)以 error 信号结束
     */
    Mono<CallLeg> originate(String callee, String callerId);

    /**
     * 是否忽略早期媒体。
     *
     * <p>置 true 时只有真接通(200 OK)才会 emit {@link CallLeg} —— 这是挡掉彩铃最可靠的一层,
     * 比任何音频特征判定都准。除非要专门采集彩铃样本做 CPA 训练, 否则生产上应保持 true。
     */
    default boolean ignoreEarlyMedia() {
        return true;
    }
}
