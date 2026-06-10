package com.vca.domain.spi;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AsrEvent;
import com.vca.domain.model.AudioFrame;
import reactor.core.publisher.Flux;

/**
 * 流式语音识别厂商接口。每个厂商一个实现, 由 gateway 收集注册。
 *
 * <p>契约:
 * <ul>
 *   <li>输入是<b>持续的音频帧流</b>, 实现应边收边送厂商, 不要缓冲整段;</li>
 *   <li>输出流应先吐若干 partial(isFinal=false)再吐 final(isFinal=true);</li>
 *   <li>收到 {@link AudioFrame#endOfSpeech()} 帧后应尽快产出 final 并完成流;</li>
 *   <li>订阅被取消(用户打断)时, 实现必须释放与厂商的连接。</li>
 * </ul>
 */
public interface AsrProvider {

    VendorType vendor();

    default Capability capability() {
        return Capability.ASR;
    }

    /**
     * @param audio 上行音频帧流(已 VAD 过滤)
     * @param cfg   识别参数(语言/热词/采样率)
     * @return 识别事件流(partial... -> final)
     */
    Flux<AsrEvent> transcribe(Flux<AudioFrame> audio, AsrConfig cfg);
}
