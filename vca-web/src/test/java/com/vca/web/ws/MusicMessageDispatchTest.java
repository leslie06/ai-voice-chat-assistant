package com.vca.web.ws;

import com.vca.domain.model.MusicPlaylist;
import com.vca.domain.model.MusicTrack;
import com.vca.domain.spi.MusicProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 音乐请求的分流规则。
 *
 * <p>这个测试是补一个真实线上 bug 的: 语音说"下一首"时页面弹出"没找到《》"。根因是接入层
 * <b>忽略了 action 参数</b>, 无条件把入参当搜索词去调音源 —— 而控歌的 query 是空串, 必然搜不到。
 * 编排层那边的测试是在 TurnListener 边界断言的(动作确实发出去了), 恰好越过了这一层, 所以没兜住。
 */
class MusicMessageDispatchTest {

    /** 记录音源被调了几次的桩; 控歌时它一次都不该被碰。 */
    private static class CountingProvider implements MusicProvider {
        final AtomicInteger calls = new AtomicInteger();
        final MusicPlaylist result;

        CountingProvider(MusicPlaylist result) {
            this.result = result;
        }

        @Override
        public Mono<MusicPlaylist> playlist(String query) {
            calls.incrementAndGet();
            return result == null ? Mono.empty() : Mono.just(result);
        }

        @Override
        public Mono<MusicTrack> search(String query) {
            calls.incrementAndGet();   // 走到这里也算碰了音源
            return Mono.empty();
        }
    }

    private static MusicPlaylist onePlaylist() {
        return new MusicPlaylist(
                List.of(new MusicTrack("晴天", "周杰伦", "https://x/a.mp3", null, null, 240, true)), 0);
    }

    @Test
    void controlActionsAreForwardedWithoutTouchingTheMusicSource() {
        for (String action : new String[]{"next", "previous", "pause", "resume", "stop"}) {
            CountingProvider provider = new CountingProvider(null);
            Map<String, Object> msg = VoiceWebSocketHandler.musicMessage(provider, action, "").block();

            assertEquals("music", msg.get("type"));
            assertEquals(action, msg.get("action"));
            // 关键: 控歌没有要搜的歌, 拿空串去搜必然搜不到 —— 那正是"没找到《》"的来源
            assertEquals(0, provider.calls.get(), "控歌不该调音源: " + action);
            assertFalse(msg.containsKey("query"), "控歌消息不该带 query: " + action);
        }
    }

    @Test
    void playStillSearchesTheMusicSource() {
        CountingProvider provider = new CountingProvider(onePlaylist());
        Map<String, Object> msg = VoiceWebSocketHandler.musicMessage(provider, "play", "晴天").block();

        assertEquals(1, provider.calls.get());
        assertEquals("play", msg.get("action"));
        assertEquals("晴天", msg.get("title"));
    }

    @Test
    void playFallsBackToNotFoundWhenSourceHasNothing() {
        CountingProvider provider = new CountingProvider(null);
        Map<String, Object> msg = VoiceWebSocketHandler.musicMessage(provider, "play", "不存在的歌").block();

        assertEquals("notfound", msg.get("action"));
        assertEquals("不存在的歌", msg.get("query"));
    }

    @Test
    void playSurvivesMusicSourceFailure() {
        MusicProvider boom = new MusicProvider() {
            @Override
            public Mono<MusicTrack> search(String query) {
                return Mono.error(new IllegalStateException("音源挂了"));
            }

            @Override
            public Mono<MusicPlaylist> playlist(String query) {
                return Mono.error(new IllegalStateException("音源挂了"));
            }
        };
        Map<String, Object> msg = VoiceWebSocketHandler.musicMessage(boom, "play", "晴天").block();

        assertEquals("notfound", msg.get("action"));   // 音源故障不该把整轮打挂
    }
}
