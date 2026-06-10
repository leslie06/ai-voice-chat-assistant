package com.vca.orchestrator.skill;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 点歌意图识别(关键词路由, MVP)。
 *
 * <p>从"本轮用户说了什么"里抽取出想听的歌(歌名/歌手)。命中即短路普通 LLM 对话,
 * 改由 {@code ConversationSession} 通知前端去 QQ 音乐播放。
 *
 * <p>这是确定性的关键词方案: 零额外延迟、好维护, 但语句要大致符合触发词。
 * 想听懂"放首适合下雨天的歌"这类模糊表达, 可后续升级为 LLM function-calling。
 * 触发词与清洗规则都集中在这里, 方便按实际语料调优。
 */
public final class MusicIntent {

    /** 点歌触发词: 后面紧跟歌名/歌手。尽量选音乐场景专属的词以降低误触发。 */
    private static final Pattern PLAY = Pattern.compile(
            "(?:播放|放一首|放首|来一首|来首|点歌|点一首|我想听|我要听)\\s*(.+)");

    /** 去掉抽取结果开头的冗余量词/类目词 */
    private static final Pattern LEADING = Pattern.compile("^(?:的|首|一首|歌曲|歌|音乐)\\s*");
    /** 去掉结尾的语气词/类目词/标点 */
    private static final Pattern TRAILING = Pattern.compile("(?:这首歌|的歌|这首|歌曲|的音乐|音乐|吧|呗|啊|谢谢|[。！？!?,，.\\s])+$");

    /**
     * 解析点歌意图。
     *
     * @return 想听的歌(已清洗的查询词), 非点歌或抽不出歌名时返回 {@link Optional#empty()}
     */
    public Optional<String> parsePlay(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PLAY.matcher(text.trim());
        if (!m.find()) {
            return Optional.empty();
        }
        String q = m.group(1).trim();
        q = LEADING.matcher(q).replaceAll("");
        q = TRAILING.matcher(q).replaceAll("").trim();
        // 抽出来是空(如"我想听歌")或过长(更像一句正常对话, 非歌名)时不当作点歌
        if (q.isBlank() || q.length() > 30) {
            return Optional.empty();
        }
        return Optional.of(q);
    }
}
