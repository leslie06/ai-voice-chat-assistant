package com.vca.domain.spi;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 端到端语音大模型接口(Speech-to-Speech): 直接吞音频、吐音频, 内部融合 ASR+LLM+TTS。
 * 作为三段式之外的可选模式, 同样藏在 SPI 之后, 上层编排可一键切换。
 *
 * <p>契约: 输入上行音频帧流, 输出下行音频块流; 厂商内部自行处理识别/理解/合成/打断。
 */
public interface S2sProvider {

    VendorType vendor();

    default Capability capability() {
        return Capability.S2S;
    }

    /**
     * @param audio   上行音频帧流(本轮用户说话)
     * @param history 在此之前的对话历史(system + 多轮 user/assistant), 顺序即时间序。
     *                端到端模型每路连接是无状态的, 厂商实现应把历史回灌(如 conversation items),
     *                否则模型记不住上下文、并会把每轮都当成"对话开始"。可为空(首轮)。
     * @param cfg     端到端参数(模型/音色/人设)
     * @return 下行音频块流
     */
    Flux<AudioChunk> converse(Flux<AudioFrame> audio, List<Message> history, S2sConfig cfg);
}
