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

    /**
     * 预热: 回合一开始(用户刚说完、还在识别)就把合成连接建起来, 让建连与 ASR/LLM 重叠。
     *
     * <p>为什么值得单开一个方法: 云厂商的合成要新建 WebSocket, 实测握手约 1.2 秒, 而合成首帧只要 0.6 秒。
     * 等第一句话生成出来再建连, 这 1.2 秒完整落在用户的等待里; 提前建好就几乎不可见。
     *
     * <p>约定: <b>调用它永远是可选的、无害的</b> —— 默认空实现; 预热了但没用上(空回复/打断/换了厂商)
     * 由实现方自己回收。所以调用方不需要处理返回值, 也不需要成对调用。
     */
    default void prewarm(TtsConfig cfg) {
        // 默认不预热
    }
}
