package com.vca.domain.spi;

import com.vca.domain.model.AudioFrame;
import com.vca.domain.model.S2sEvent;
import reactor.core.publisher.Flux;

/**
 * 端到端语音<b>持久全双工会话</b>句柄。一条长连贯穿整段对话(多轮), 由<b>服务端 VAD</b>接管
 * 回合切分与打断 —— 不再像 {@code S2sProvider.converse} 那样"每轮一条连接、应用侧判停"。
 *
 * <p>生命周期: {@link S2sProvider#open} 取得句柄 → 订阅 {@link #events()} 触发建连 →
 * 持续 {@link #pushAudio} 喂麦克风音频 → 期间从 {@code events()} 收到音频/字幕/打断信号 →
 * {@link #close()} 结束。
 *
 * <p>线程: {@link #pushAudio} 由接入层(音频接收线程)持续调用; {@code events()} 的事件可能在
 * 厂商 WebSocket 回调线程上产生 —— 实现需自行保证两侧线程安全。
 */
public interface S2sSession {

    /**
     * 持续上行: 把麦克风采集的一帧音频喂入会话。无"轮"的概念 —— 何时算说完、何时回复, 由服务端 VAD 决定。
     * 连接尚未就绪时实现应缓冲, 就绪后补发。空帧/结束帧忽略。
     */
    void pushAudio(AudioFrame frame);

    /**
     * 下行事件流。<b>订阅即触发建连</b>(冷流), 全程只应被订阅一次。长连期间多次发射
     * {@link S2sEvent} 各变体; 服务端结束会话时 onComplete, 致命错误时 onError。
     */
    Flux<S2sEvent> events();

    /**
     * 主动打断(手动按钮): 截断机器人当前回复。服务端 VAD 触发的打断由实现内部处理,
     * 并经 {@code events()} 发 {@link S2sEvent.UserSpeechStarted} 通知, 无需调用方再调本方法。
     */
    void cancelResponse();

    /**
     * 回灌一次工具调用的结果(function-calling)。在收到 {@link S2sEvent.FunctionCall} 并执行完技能后调用:
     * 实现把结果作为 {@code function_call_output} 项写回会话, 并触发模型据此生成语音回复。
     * 默认空实现(不支持工具的厂商)。
     *
     * @param callId 对应 {@link S2sEvent.FunctionCall#callId()}
     * @param output 工具结果文本(数据型: 给模型作答的依据; 动作型: 简短执行确认)
     */
    default void submitToolResult(String callId, String output) {
    }

    /** 关闭会话并释放底层连接。幂等。 */
    void close();
}
