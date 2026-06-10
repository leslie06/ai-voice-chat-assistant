package com.vca.orchestrator.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;

/**
 * 一轮对话的关键延迟埋点。实时语音助手最关心三个时刻:
 * <ul>
 *   <li><b>LLM 首 token</b>: 从拿到用户输入到大模型吐第一个字 —— 思考延迟;</li>
 *   <li><b>TTS 首包</b>: 从拿到用户输入到第一帧音频 —— <b>用户感知的"开口延迟"</b>, 最关键;</li>
 *   <li><b>整轮耗时</b>: 一轮从开始到结束(含播放/取消)。</li>
 * </ul>
 *
 * <p>只依赖 Micrometer 门面, 不绑定具体后端。宿主注入带 Prometheus 等后端的
 * {@link MeterRegistry}; 测试/未启用时用 {@link #noop()} 走内存态 registry。
 */
public final class TurnMetrics {

    private final MeterRegistry registry;
    private final Timer llmFirstToken;
    private final Timer ttsFirstAudio;
    private final Timer turnTotal;

    public TurnMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.llmFirstToken = Timer.builder("vca.turn.llm.first_token")
                .description("从拿到用户输入到 LLM 吐出第一个 token 的耗时")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.ttsFirstAudio = Timer.builder("vca.turn.tts.first_audio")
                .description("从拿到用户输入到第一帧 TTS 音频(用户感知开口延迟)")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.turnTotal = Timer.builder("vca.turn.total")
                .description("一轮对话从开始到结束的总耗时")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    /** 无后端埋点(测试/未启用 metrics 时用), 走内存态 SimpleMeterRegistry。 */
    public static TurnMetrics noop() {
        return new TurnMetrics(new SimpleMeterRegistry());
    }

    public void recordLlmFirstToken(Duration d) {
        llmFirstToken.record(d);
    }

    public void recordTtsFirstAudio(Duration d) {
        ttsFirstAudio.record(d);
    }

    public void recordTurnTotal(Duration d) {
        turnTotal.record(d);
    }

    /** 计一轮结束: type=voice|text, outcome=complete|interrupted|error。 */
    public void countTurn(String type, String outcome) {
        registry.counter("vca.turn.count", "type", type, "outcome", outcome).increment();
    }
}
