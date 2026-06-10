package com.vca.domain.spi;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.TtsConfig;
import reactor.core.publisher.Flux;

/**
 * 流式语音合成厂商接口。
 *
 * <p>契约:
 * <ul>
 *   <li>输入是<b>文本片段流</b>(上层已分句), 实现应对每个片段流式合成、流式回吐音频块;</li>
 *   <li>不要等收齐全部文本再合成 —— 那会摧毁低延迟;</li>
 *   <li>首音频块延迟越低越好(直接影响"听到回复"的体感);</li>
 *   <li>订阅取消(打断)时必须停止合成并释放连接。</li>
 * </ul>
 */
public interface TtsProvider {

    VendorType vendor();

    default Capability capability() {
        return Capability.TTS;
    }

    /**
     * @param textSegments 文本片段流(通常是一句一段)
     * @param cfg          合成参数(音色/格式/语速)
     * @return 音频块流
     */
    Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg);
}
