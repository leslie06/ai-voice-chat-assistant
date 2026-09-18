package com.vca.telephony.summary;

import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import com.vca.domain.spi.LlmProvider;
import com.vca.orchestrator.call.CallSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 用大模型把一通电话压成"两三句摘要 + 一个意向等级"。
 *
 * <p><b>为什么不要 JSON</b>: 让模型输出 JSON 就得处理它偶尔加 markdown 代码围栏、偶尔漏引号的情况,
 * 而这里只要三个字段。改成固定的三行前缀格式("摘要:/意向:/跟进:"), 解析就是按行取前缀, 少一个依赖、少一类故障。
 * 解析失败也不算失败 —— 退化成"把模型原话当摘要、意向记 C", 商家仍能看到有用的东西。
 */
public final class CallSummarizer {

    private static final Logger log = LoggerFactory.getLogger(CallSummarizer.class);

    /** 摘要的人设与输出格式。放成 public 是为了让配置侧组装 {@code LlmConfig} 时直接用它, 不必两处维护。 */
    public static final String PROMPT = """
            你是电话客服的值班主管, 把一通刚结束的通话压成一条给老板看的小结。

            只输出三行, 每行一个字段, 冒号后直接写内容:
            摘要: 两三句话说清客户是谁、要什么、有没有结论。不要复述寒暄。
            意向: 只写一个大写字母, 不要写任何解释。
            A=已经约了时间、留了电话要求回电、或明确说要到店(哪怕时间还要再确认, 也算 A);
            B=有兴趣但没定下来(问了价格还要再想想、说改天再说);
            C=只是问问(问地址/营业时间/能不能做某项目, 没表达要办);
            D=无效(骚扰、拨错、没说话)。
            跟进: 一句话写该做什么; 不需要跟进就写 无。

            禁止: markdown(不要 * # - 这些符号)、加粗、列表、分点、标题、解释你在做什么、输出这三行之外的任何内容。

            示例输出:
            摘要: 王先生想做种植牙, 已登记周六上午的面诊, 未留其它联系方式。
            意向: A
            跟进: 周六上午前电话确认到店时间
            """;

    /** 摘要是事后动作, 不占通话时间, 但也不能挂在那里 —— 超时就放弃这一通 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_TRANSCRIPT_CHARS = 4000;

    private final LlmProvider llm;
    private final LlmConfig config;

    public CallSummarizer(LlmProvider llm, LlmConfig config) {
        this.llm = llm;
        this.config = config;
    }

    /**
     * @param ownerId 商家账号 id(可为空: 那只是没法落库归属, 摘要照样出)
     * @return 小结; 大模型不可用时以 error 结束, 由调用方降级
     */
    public Mono<CallSummary> summarize(EndedCall call, String ownerId) {
        String transcript = transcript(call.history());
        if (transcript.isBlank()) {
            // 一句话都没说(秒挂/彩铃/静音): 不值得调模型
            return Mono.just(new CallSummary(call.callId(), ownerId, call.peerNumber(), call.calledNumber(),
                    call.durationSec(), 0, "客户接通后没有说话。", "D", null, LocalDateTime.now()));
        }
        // 格式要求在 system 与 user 里各说一遍: 小模型对 system 的遵守度明显差一些(实测有模型直接输出 markdown 分点)
        List<Message> input = List.of(Message.user("通话记录:\n" + transcript
                + "\n\n通话时长 " + call.durationSec() + " 秒, 结束原因 " + call.reason() + "。"
                + "\n\n按 摘要: / 意向: / 跟进: 三行输出, 不要 markdown, 不要分点。"
                + "意向那行只写 A、B、C、D 其中一个字母。"));
        // 摘要要的是完整文本, 不是流 —— 聚合完再解析
        return llm.chatStream(input, config)
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .timeout(TIMEOUT)
                // 模型原话留一行 debug: 小结出问题时(格式没遵守、意向判错)第一件事就是看它到底说了什么
                .doOnNext(reply -> log.debug("[{}] 小结原始输出: {}", call.callId(), reply.replace("\n", " | ")))
                .map(reply -> parse(reply, call, ownerId, countTurns(call.history())))
                .doOnError(e -> log.warn("[{}] 生成通话小结失败: {}", call.callId(), e.toString()));
    }

    /** 只留客户与 AI 的对话, 丢掉人设与各路注入的 system 上下文(知识库/时间等, 对摘要是噪声) */
    private static String transcript(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        if (history == null) {
            return "";
        }
        for (Message m : history) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            String who = switch (m.role()) {
                case USER -> "客户";
                case ASSISTANT -> "客服";
                default -> null;
            };
            if (who == null) {
                continue;
            }
            sb.append(who).append(": ").append(m.content().strip()).append('\n');
            if (sb.length() > MAX_TRANSCRIPT_CHARS) {
                break;   // 超长通话截断: 开头那几轮已经足够看出意向
            }
        }
        return sb.toString();
    }

    private static int countTurns(List<Message> history) {
        if (history == null) {
            return 0;
        }
        return (int) history.stream().filter(m -> m != null && m.role() == Message.Role.USER).count();
    }

    private static CallSummary parse(String reply, EndedCall call, String ownerId, int turns) {
        String summary = null;
        String intent = null;
        String followUp = null;
        for (String line : reply.split("\n")) {
            String t = line.strip();
            if (t.startsWith("摘要:") || t.startsWith("摘要：")) {
                summary = t.substring(3).strip();
            } else if (t.startsWith("意向:") || t.startsWith("意向：")) {
                intent = grade(t.substring(3));
            } else if (t.startsWith("跟进:") || t.startsWith("跟进：")) {
                followUp = t.substring(3).strip();
            }
        }
        if (summary == null || summary.isBlank()) {
            // 没按格式来: 把原话当摘要, 别丢信息
            summary = reply.strip();
        }
        if (followUp != null && (followUp.isBlank() || "无".equals(followUp))) {
            followUp = null;
        }
        return new CallSummary(call.callId(), ownerId, call.peerNumber(), call.calledNumber(),
                call.durationSec(), turns, summary, intent == null ? "C" : intent, followUp,
                LocalDateTime.now());
    }

    /**
     * 取意向等级。模型可能写成 {@code A}、{@code a}、{@code A(马上要办)}, 也可能<b>压根不写字母</b> ——
     * 实测 qwen-flash 会把这一行写成"明确表达种植牙面诊需求，有明确时间意向。"这样一句中文。
     * 所以先找字母, 找不到再按词判; 都判不出才退回 null(调用方按 C 处理)。
     */
    private static String grade(String raw) {
        String t = raw.trim();
        for (char c : t.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'D') {
                return String.valueOf(c);
            }
        }
        if (containsAny(t, "无效", "骚扰", "打错", "拨错", "没说话", "未说话", "空号")) {
            return "D";
        }
        if (containsAny(t, "已约", "已预约", "预约", "要求回电", "马上", "立刻", "尽快到店", "确认时间")) {
            return "A";
        }
        if (containsAny(t, "有意向", "考虑", "待跟进", "可跟进", "潜在")) {
            return "B";
        }
        if (containsAny(t, "咨询", "问问", "了解", "询价")) {
            return "C";
        }
        return null;
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
