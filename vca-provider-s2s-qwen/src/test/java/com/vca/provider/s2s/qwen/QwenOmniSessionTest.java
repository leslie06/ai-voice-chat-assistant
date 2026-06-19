package com.vca.provider.s2s.qwen;

import com.google.gson.JsonObject;
import com.vca.domain.model.Message;
import com.vca.domain.model.S2sEvent;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线单测: 覆盖持久会话里的两处确定性纯逻辑 —— 服务端事件→{@link S2sEvent} 映射、历史→conversation item 构造。
 * 真实的长连往返(server VAD / 打断)需 DASHSCOPE_API_KEY + Omni 实时权限, 不在单测范围。
 */
class QwenOmniSessionTest {

    private final AtomicLong seq = new AtomicLong();

    private static JsonObject event(String type) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        return e;
    }

    @Test
    void audioDeltaDecodesAndAdvancesSequence() {
        byte[] pcm = {0, 1, 2, 3, 64, -1, -128, 127};
        JsonObject e = event("response.audio.delta");
        e.addProperty("delta", Base64.getEncoder().encodeToString(pcm));

        S2sEvent ev = QwenOmniSession.mapEvent(e, "response.audio.delta", seq);

        assertThat(ev).isInstanceOf(S2sEvent.AudioDelta.class);
        S2sEvent.AudioDelta delta = (S2sEvent.AudioDelta) ev;
        assertThat(delta.pcm()).containsExactly(pcm);
        assertThat(delta.sequence()).isZero();
        // 序号自增: 下一块音频拿到 1
        e.addProperty("delta", Base64.getEncoder().encodeToString(pcm));
        assertThat(((S2sEvent.AudioDelta) QwenOmniSession.mapEvent(e, "response.audio.delta", seq)).sequence())
                .isEqualTo(1);
    }

    @Test
    void assistantAndUserTranscriptsMap() {
        JsonObject reply = event("response.audio_transcript.delta");
        reply.addProperty("delta", "你好");
        assertThat(QwenOmniSession.mapEvent(reply, "response.audio_transcript.delta", seq))
                .isEqualTo(new S2sEvent.AssistantText("你好"));

        JsonObject userText = event("conversation.item.input_audio_transcription.completed");
        userText.addProperty("transcript", "今天天气怎么样");
        assertThat(QwenOmniSession.mapEvent(userText, "conversation.item.input_audio_transcription.completed", seq))
                .isEqualTo(new S2sEvent.UserTranscript("今天天气怎么样"));
    }

    @Test
    void responseDoneMapsButDoesNotTerminate() {
        assertThat(QwenOmniSession.mapEvent(event("response.done"), "response.done", seq))
                .isEqualTo(new S2sEvent.ResponseDone());
    }

    @Test
    void blankAndUnknownEventsAreIgnored() {
        // 空增量不产生事件
        assertThat(QwenOmniSession.mapEvent(event("response.audio.delta"), "response.audio.delta", seq)).isNull();
        // 握手类事件忽略
        assertThat(QwenOmniSession.mapEvent(event("response.created"), "response.created", seq)).isNull();
        assertThat(QwenOmniSession.mapEvent(event("session.created"), "session.created", seq)).isNull();
    }

    @Test
    void historyItemShapesByRole() {
        JsonObject user = QwenOmniSession.historyItem(Message.user("在吗"));
        assertThat(user.get("type").getAsString()).isEqualTo("message");
        assertThat(user.get("role").getAsString()).isEqualTo("user");
        JsonObject userContent = user.getAsJsonArray("content").get(0).getAsJsonObject();
        assertThat(userContent.get("type").getAsString()).isEqualTo("input_text");
        assertThat(userContent.get("text").getAsString()).isEqualTo("在吗");

        // 助手文本用 text(输出内容类型), 区别于用户/系统的 input_text
        JsonObject assistant = QwenOmniSession.historyItem(Message.assistant("在的"));
        assertThat(assistant.get("role").getAsString()).isEqualTo("assistant");
        assertThat(assistant.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString())
                .isEqualTo("text");

        JsonObject system = QwenOmniSession.historyItem(Message.system("你是助手"));
        assertThat(system.get("role").getAsString()).isEqualTo("system");
        assertThat(system.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString())
                .isEqualTo("input_text");
    }
}
