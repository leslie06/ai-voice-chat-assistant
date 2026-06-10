package com.vca.domain.model;

/**
 * ASR 识别事件。流式识别会先吐若干"中间假设"(isFinal=false, 用于实时字幕),
 * 再吐一个"最终结果"(isFinal=true, 触发 LLM)。
 *
 * @param text       本次识别文本
 * @param isFinal    是否为最终结果(true 时上层应触发 LLM)
 * @param offsetMs   相对本轮说话起点的时间偏移(毫秒)
 * @param confidence 置信度 [0,1], 多厂商兜底时用于择优; 厂商不提供时为 -1
 */
public record AsrEvent(
        String text,
        boolean isFinal,
        long offsetMs,
        double confidence
) {
    public static AsrEvent partial(String text, long offsetMs) {
        return new AsrEvent(text, false, offsetMs, -1);
    }

    public static AsrEvent finalResult(String text, long offsetMs, double confidence) {
        return new AsrEvent(text, true, offsetMs, confidence);
    }

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
