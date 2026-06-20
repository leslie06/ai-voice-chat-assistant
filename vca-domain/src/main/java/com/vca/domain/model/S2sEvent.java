package com.vca.domain.model;

/**
 * 端到端语音<b>持久会话</b>的下行事件。区别于每轮一调的 {@code S2sProvider.converse}(单请求单响应流),
 * 持久会话({@link com.vca.domain.spi.S2sSession})在一条长连里多次发射不同类型的事件 —— 因此需要一个
 * 带类型的事件模型, 而不是靠"空音频块塞字幕"的约定。
 *
 * <p>各变体语义:
 * <ul>
 *   <li>{@link AudioDelta}: 一段下行音频(PCM), 边收边播;</li>
 *   <li>{@link AssistantText}: 机器人回复的转写增量(字幕);</li>
 *   <li>{@link UserTranscript}: 服务端对用户语音的转写(显示"你说了什么");</li>
 *   <li>{@link UserSpeechStarted}: <b>服务端 VAD 判定用户开口</b> —— 全双工打断的触发点,
 *       接入层应据此立即冲掉前端播放缓冲、止住机器人当前回复;</li>
 *   <li>{@link FunctionCall}: 模型发起一次工具调用(function-calling) —— 编排层据此执行技能,
 *       再经 {@link com.vca.domain.spi.S2sSession#submitToolResult} 把结果回灌, 模型据此继续语音作答;</li>
 *   <li>{@link ResponseDone}: 机器人本次回复结束(会话<b>不</b>关闭, 等待下一轮)。</li>
 * </ul>
 *
 * <p>致命错误经 Reactor 的错误通道(onError)传播, 不在此建模; 会话正常终结经 onComplete。
 */
public sealed interface S2sEvent {

    /** 下行音频块。 */
    record AudioDelta(byte[] pcm, long sequence) implements S2sEvent {}

    /**
     * 模型发起的一次工具调用(function-calling)。编排层执行对应技能后, 须用 {@code callId} 把结果
     * 经 {@link com.vca.domain.spi.S2sSession#submitToolResult} 回灌给会话, 模型据此继续语音回复。
     *
     * @param callId    调用 id(回灌结果时配对用)
     * @param name      工具名(对应某 Skill)
     * @param arguments 调用参数(原始 JSON 字符串)
     */
    record FunctionCall(String callId, String name, String arguments) implements S2sEvent {}

    /** 机器人回复转写增量(字幕)。 */
    record AssistantText(String delta) implements S2sEvent {}

    /** 用户语音转写(服务端识别出的"用户说了什么")。 */
    record UserTranscript(String text) implements S2sEvent {}

    /** 服务端 VAD 判定用户开口: 全双工打断信号。 */
    record UserSpeechStarted() implements S2sEvent {}

    /** 机器人本次回复结束(会话继续)。 */
    record ResponseDone() implements S2sEvent {}
}
