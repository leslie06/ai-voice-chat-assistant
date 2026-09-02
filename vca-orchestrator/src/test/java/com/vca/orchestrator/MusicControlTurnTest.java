package com.vca.orchestrator;

import com.vca.domain.enums.AudioFormat;
import com.vca.domain.enums.VendorType;
import com.vca.domain.model.AsrConfig;
import com.vca.domain.model.AudioChunk;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.model.SessionContext;
import com.vca.domain.model.TtsConfig;
import com.vca.domain.spi.LlmProvider;
import com.vca.domain.spi.TtsProvider;
import com.vca.orchestrator.pipeline.SentenceSplitter;
import com.vca.orchestrator.session.ConversationSession;
import com.vca.orchestrator.session.TurnListener;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语音控歌("下一首/切歌")的回合行为。
 *
 * <p>核心约束是<b>只在真的在放歌时才当命令</b>: 没在放歌时"下一首"多半是正常对话的一部分
 * (如"下一首歌是什么"), 被当成命令吞掉就是功能变成 bug。
 */
class MusicControlTurnTest {

    private static final AtomicInteger LLM_CALLS = new AtomicInteger();

    private static LlmProvider countingLlm() {
        return new LlmProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.DEEPSEEK;
            }

            @Override
            public Flux<String> chatStream(List<Message> history, LlmConfig cfg) {
                LLM_CALLS.incrementAndGet();
                return Flux.just("下", "一", "首", "是", "夜", "曲", "。");
            }
        };
    }

    private static TtsProvider echoTts() {
        return new TtsProvider() {
            @Override
            public VendorType vendor() {
                return VendorType.ALIYUN;
            }

            @Override
            public Flux<AudioChunk> synthesize(Flux<String> textSegments, TtsConfig cfg) {
                return textSegments.map(seg -> new AudioChunk(
                        seg.getBytes(StandardCharsets.UTF_8), AudioFormat.PCM, 0, seg, false));
            }
        };
    }

    /** 记录下发给前端的动作。 */
    private record Action(String action, String query) {
    }

    /** 音量动作单独记, 走的是另一条端口。 */
    private static final List<String> VOLUME = new CopyOnWriteArrayList<>();

    private static ConversationSession session(List<Action> actions) {
        SessionContext ctx = SessionContext.pipeline(
                "s-music", "u-1",
                AsrConfig.defaults(VendorType.ALIYUN),
                LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                TtsConfig.defaults(VendorType.ALIYUN, "longxiaochun"));
        ConversationSession s = new ConversationSession(
                ctx, null, countingLlm(), echoTts(), null, new SentenceSplitter());
        s.setTurnListener(new TurnListener() {
            @Override
            public void onMusicRequest(String action, String query) {
                actions.add(new Action(action, query));
            }

            @Override
            public void onVolumeRequest(String direction) {
                VOLUME.add(direction);
            }
        });
        return s;
    }

    @Test
    void skipCommandWhilePlayingEmitsActionWithoutLlmOrSpeech() {
        LLM_CALLS.set(0);
        List<Action> actions = new CopyOnWriteArrayList<>();
        ConversationSession session = session(actions);
        session.setMusicState(true, true);

        List<AudioChunk> audio = session.handleTextTurn("下一首").collectList().block();

        assertThat(actions).containsExactly(new Action("next", ""));
        assertThat(LLM_CALLS).hasValue(0);              // 不经模型, 零延迟直达
        assertThat(audio).isEmpty();                    // 不念确认语: 会盖在音乐上, 且下一首起播就是反馈
        // 动作是副作用不是对话内容: 除人设外不留痕, 免得模型从历史里仿写确认语
        assertThat(session.historyView().stream().filter(m -> m.role() != Message.Role.SYSTEM))
                .isEmpty();
    }

    @Test
    void skipCommandWhenNothingIsPlayingFallsThroughToNormalChat() {
        LLM_CALLS.set(0);
        List<Action> actions = new CopyOnWriteArrayList<>();
        ConversationSession session = session(actions);
        session.setMusicState(false, false);   // 没有当前曲目

        session.handleTextTurn("下一首").collectList().block();

        assertThat(actions).isEmpty();     // 不下发切歌动作
        assertThat(LLM_CALLS).hasValue(1); // 原样交给模型当普通对话
    }

    @Test
    void normalQuestionIsNotHijackedEvenWhilePlaying() {
        LLM_CALLS.set(0);
        List<Action> actions = new CopyOnWriteArrayList<>();
        ConversationSession session = session(actions);
        session.setMusicState(true, true);

        session.handleTextTurn("下一首歌是什么").collectList().block();

        assertThat(actions).isEmpty();
        assertThat(LLM_CALLS).hasValue(1);
    }

    @Test
    void resumeOnlyCountsWhilePaused() {
        // "继续"是日常高频词("继续说"), 音乐正响着时用户说"继续"几乎不可能是指音乐 —— 必须放给模型
        LLM_CALLS.set(0);
        List<Action> actions = new CopyOnWriteArrayList<>();
        ConversationSession playing = session(actions);
        playing.setMusicState(true, true);
        playing.handleTextTurn("继续").collectList().block();
        assertThat(actions).isEmpty();
        assertThat(LLM_CALLS).hasValue(1);

        // 已暂停时"继续"才是恢复播放
        List<Action> paused = new CopyOnWriteArrayList<>();
        ConversationSession s2 = session(paused);
        s2.setMusicState(true, false);
        s2.handleTextTurn("继续").collectList().block();
        assertThat(paused).containsExactly(new Action("resume", ""));
    }

    @Test
    void pauseOnlyCountsWhilePlaying() {
        List<Action> paused = new CopyOnWriteArrayList<>();
        ConversationSession s1 = session(paused);
        s1.setMusicState(true, false);          // 已经停了还说"暂停" → 更可能在讲别的事
        s1.handleTextTurn("暂停").collectList().block();
        assertThat(paused).isEmpty();

        List<Action> playing = new CopyOnWriteArrayList<>();
        ConversationSession s2 = session(playing);
        s2.setMusicState(true, true);
        s2.handleTextTurn("暂停").collectList().block();
        assertThat(playing).containsExactly(new Action("pause", ""));
    }

    @Test
    void skipAndStopWorkEvenWhilePaused() {
        // 暂停状态下切歌/停止播放都是合理操作, 不该被门闸挡掉
        List<Action> a = new CopyOnWriteArrayList<>();
        ConversationSession s1 = session(a);
        s1.setMusicState(true, false);
        s1.handleTextTurn("上一首").collectList().block();
        s1.handleTextTurn("停止播放").collectList().block();
        assertThat(a).containsExactly(new Action("previous", ""), new Action("stop", ""));
    }

    @Test
    void playRequestStillWorksWhilePlaying() {
        LLM_CALLS.set(0);
        List<Action> actions = new CopyOnWriteArrayList<>();
        ConversationSession session = session(actions);
        session.setMusicState(true, true);

        session.handleTextTurn("我想听七里香").collectList().block();

        // 控歌排在点歌之前, 但不能把点歌也吃掉
        assertThat(actions).containsExactly(new Action("play", "七里香"));
    }

    @Test
    void volumeCommandsWorkRegardlessOfPlaybackState() {
        // 音量不设状态门闸: 没在放歌时"声音大点"指的是助手说话的音量, 同样成立
        for (boolean active : new boolean[]{true, false}) {
            VOLUME.clear();
            LLM_CALLS.set(0);
            List<Action> actions = new CopyOnWriteArrayList<>();
            ConversationSession session = session(actions);
            session.setMusicState(active, active);

            List<AudioChunk> audio = session.handleTextTurn("声音大点").collectList().block();

            assertThat(VOLUME).as("active=" + active).containsExactly("up");
            assertThat(actions).isEmpty();       // 不是控歌动作
            assertThat(LLM_CALLS).hasValue(0);   // 不经模型
            assertThat(audio).isEmpty();         // 不念确认语: 音量变了本身就是反馈
            assertThat(session.historyView().stream().filter(m -> m.role() != Message.Role.SYSTEM))
                    .isEmpty();                  // 副作用不进历史
        }
    }

    @Test
    void volumeQuestionIsNotHijacked() {
        VOLUME.clear();
        LLM_CALLS.set(0);
        ConversationSession session = session(new CopyOnWriteArrayList<>());
        session.setMusicState(true, true);

        session.handleTextTurn("怎么调音量").collectList().block();

        assertThat(VOLUME).isEmpty();
        assertThat(LLM_CALLS).hasValue(1);   // 原样交给模型回答
    }
}
