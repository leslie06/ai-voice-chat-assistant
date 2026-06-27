package com.vca.orchestrator.recorder;

/**
 * 对话存档端口: 编排层每完成一轮就把 {@link TurnRecord} 交给它落库, 形成"数据→评测→改进"飞轮的第一环。
 *
 * <p>与 {@link com.vca.orchestrator.session.TurnListener} 一样是<b>默认空实现</b>的旁路端口 ——
 * 不注入实现({@link #NOOP})时编排行为完全不变, 故对现有三段式/S2S 链路零侵入、可随开关回退。
 *
 * <p><b>契约</b>: 实现必须<b>异步、不阻塞</b>调用线程(落库不能拖慢语音热路径), 且<b>自行吞掉异常</b> ——
 * 存档失败绝不能影响正在进行的对话。具体实现见 {@code vca-store} 模块的 JDBC 落库器。
 */
@FunctionalInterface
public interface ConversationRecorder {

    /** 不落库(未启用持久化时用): 丢弃所有记录。 */
    ConversationRecorder NOOP = record -> {
    };

    /** 提交一轮对话存档。实现须立即返回(异步入队), 不得抛异常。 */
    void recordTurn(TurnRecord record);
}
