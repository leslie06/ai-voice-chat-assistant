package com.vca.domain.spi;

import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 大模型对话厂商接口。国内多数厂商提供 OpenAI 兼容的 SSE 流式接口,
 * 实现通常用 WebClient 直连即可。
 *
 * <p>契约:
 * <ul>
 *   <li>返回<b>逐 token/逐片段的文本流</b>(SSE), 不要等整段生成完;</li>
 *   <li>上层会对该流做实时分句送 TTS(句子级流水线), 因此首 token 延迟(TTFT)至关重要;</li>
 *   <li>订阅取消(打断)时必须中断与厂商的 SSE 连接。</li>
 * </ul>
 */
public interface LlmProvider {

    VendorType vendor();

    default Capability capability() {
        return Capability.LLM;
    }

    /**
     * @param history 完整对话历史(system + 多轮 user/assistant), 顺序即时间序
     * @param cfg     模型参数
     * @return 文本增量流
     */
    Flux<String> chatStream(List<Message> history, LlmConfig cfg);
}
