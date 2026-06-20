package com.vca.domain.spi;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sConfig;
import com.vca.domain.model.ToolSpec;
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

    /**
     * 开一条<b>持久全双工会话</b>(跨多轮复用一条连接, 服务端 VAD 接管回合与打断), 取代 {@link #converse}
     * "每轮一连接 + 应用侧判停"的伪级联用法。这是把端到端模型用出原生能力(全双工/原生打断/低延迟地板)的入口。
     *
     * <p>默认不支持(抛异常), 仅具备持久会话能力的厂商覆盖实现; 上层据此可在"持久 S2S / 每轮 S2S / 三段式"间灰度。
     *
     * @param history 会话开始前的历史(system + 多轮 user/assistant), 实现负责回灌到长连(如 conversation items)
     * @param cfg     端到端参数(模型/音色/人设)
     */
    default S2sSession open(List<Message> history, S2sConfig cfg) {
        throw new UnsupportedOperationException("持久 S2S 会话未实现: " + vendor());
    }

    /**
     * 开持久会话并启用 function-calling: 把 {@code tools} 作为会话工具下发给端到端模型,
     * 模型可在对话中发起 {@link S2sEvent.FunctionCall}, 由编排层执行后经
     * {@link S2sSession#submitToolResult} 回灌。{@code tools} 为空等价于 {@link #open(List, S2sConfig)}。
     *
     * <p>默认忽略工具、退回无工具的 {@link #open(List, S2sConfig)}, 让未适配的厂商零改动可用。
     */
    default S2sSession open(List<Message> history, List<ToolSpec> tools, S2sConfig cfg) {
        return open(history, cfg);
    }
}
