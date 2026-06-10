package com.vca.domain.enums;

/**
 * 会话状态机的状态。一轮对话的生命周期:
 * <pre>
 *   LISTENING ──(ASR final)──► THINKING ──(LLM首句)──► SPEAKING
 *       ▲                                                  │
 *       │                                                  │
 *       └──────────────(回合结束 / 用户打断)─────────────────┘
 *
 *   任意 THINKING/SPEAKING 状态下, 用户开口(VAD) ──► INTERRUPTED ──► LISTENING
 * </pre>
 */
public enum SessionState {
    /** 空闲, 等待用户开口 */
    IDLE,
    /** 正在接收用户语音并做流式识别 */
    LISTENING,
    /** 已识别完成, LLM 正在生成 (尚无可播放音频) */
    THINKING,
    /** 正在流式播放 TTS 回复 (可被打断) */
    SPEAKING,
    /** 被打断, 正在取消上游任务并清理 */
    INTERRUPTED,
    /** 会话已结束/关闭 */
    CLOSED
}
