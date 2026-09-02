package com.vca.orchestrator.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;

/**
 * 一轮对话的关键延迟埋点。
 *
 * <p><b>先看这条</b>: {@code perceivedFirstAudio} 才是用户真正感受到的延迟 —— 从<b>他闭嘴那一刻</b>
 * 到听见第一个字。其余几个都是从"后端拿到 ASR final"起表的内部耗时, 它们<b>不含句尾判停等待</b>
 * (默认近 1 秒)与 ASR 收尾, 单看会系统性低估体感延迟约 1~1.5 秒。此前只有那几个内部指标, 于是
 * 看板上一片漂亮而用户在等 —— 优化最该砍的那一段恰恰没有被测量。
 *
 * <ul>
 *   <li><b>感知首音频</b>: 用户停止说话 → 第一帧音频。<b>唯一该拿去定 SLO 的数</b>;</li>
 *   <li><b>句尾判停</b>: 本轮实际等了多久静音才认定"说完了"。配 reason 计数看语义端点判定是否真在起作用;</li>
 *   <li><b>LLM 首 token</b>: 从拿到用户输入到大模型吐第一个字 —— 思考延迟;</li>
 *   <li><b>TTS 首包</b>: 从拿到用户输入到第一帧音频 —— 后端内部耗时;</li>
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
    private final Timer perceivedFirstAudio;
    private final Timer endpointSilence;

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
        this.perceivedFirstAudio = Timer.builder("vca.turn.perceived.first_audio")
                .description("用户停止说话到听见第一帧音频(含句尾判停+ASR+LLM+TTS) —— 真·体感延迟")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.endpointSilence = Timer.builder("vca.turn.endpoint.silence")
                .description("本轮句尾判停实际等待的静音时长")
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

    /** 用户停止说话 → 第一帧音频。只有语音回合有此数(打字回合没有"停止说话"这一刻)。 */
    public void recordPerceivedFirstAudio(Duration d) {
        perceivedFirstAudio.record(d);
    }

    /**
     * 本轮句尾判停实际等了多久, 并按判定依据分桶计数。
     *
     * @param reason complete/incomplete/neutral(语义端点判定的三种归类) 或 fixed(未开启该功能)。
     *               看 {@code vca.turn.endpoint.reason} 的分布即可判断语义端点判定是否真在起作用 ——
     *               若 complete 长期为 0, 说明 ASR 中间转写里没有句末标点, 该功能只会净增延迟,
     *               此时应改为直接下调基线 silence-ms 而不是指望它。
     */
    public void recordEndpointSilence(Duration d, String reason) {
        endpointSilence.record(d);
        registry.counter("vca.turn.endpoint.reason", "reason", reason == null ? "unknown" : reason).increment();
    }

    /** 计一轮结束: type=voice|text, outcome=complete|interrupted|error。 */
    public void countTurn(String type, String outcome) {
        registry.counter("vca.turn.count", "type", type, "outcome", outcome).increment();
    }
}
