package com.vca.orchestrator.merchant;

import java.util.regex.Pattern;

/**
 * 开场白里必须让客户听到的两件事: <b>接电话的是 AI</b>, <b>这通电话会录音</b>。
 *
 * <p>依据: 《人工智能生成合成内容标识办法》(2025-09-01 施行)要求合成语音在起始等位置加语音提示;
 * 录音与留资涉及客户的声音、手机号(对诊所还有病情), 按个人信息保护法要事先告知。
 * 这两句放在开场白里代价最小: 开场白是预合成的, 多几个字不增加任何等待。
 *
 * <p>商家可以自己写开场白, 写的时候未必想得到这两点。所以这里<b>只补缺的那部分</b>:
 * 两件事都提到了就原样用; 缺哪件就在前面补哪句, 不重复啰嗦。
 */
public final class GreetingNotice {

    /**
     * 提到了 AI 身份: 智能助理/智能客服/AI/机器人 这类说法都算。
     * AI 要求是独立的大写单词, 免得店名里的拼音(如 Taiyang)被误认成已经说明了
     */
    private static final Pattern MENTIONS_AI = Pattern.compile("智能|机器人|虚拟|\\bAI\\b");
    private static final Pattern MENTIONS_RECORDING = Pattern.compile("录音");

    private GreetingNotice() {
    }

    /**
     * @param greeting 原开场白; 空 = 这家不放开场白, 原样返回
     * @return 带上 AI 身份与录音提示的开场白
     */
    public static String apply(String greeting) {
        if (greeting == null || greeting.isBlank()) {
            return greeting;
        }
        String g = greeting.strip();
        boolean ai = MENTIONS_AI.matcher(g).find();
        boolean recording = MENTIONS_RECORDING.matcher(g).find();
        if (ai && recording) {
            return g;
        }
        String prefix = !ai && !recording ? "本通电话由智能助理接听，并会录音。"
                : !ai ? "本通电话由智能助理接听。"
                : "本通电话会录音。";
        return prefix + g;
    }

    /** 没写开场白的店按店名生成的那句: 两件事直接说进去, 不用再补 */
    public static String forShop(String name, boolean withNotice) {
        return withNotice
                ? "您好，这里是" + name + "的智能助理，本通电话会录音，请问有什么可以帮您？"
                : "您好，这里是" + name + "的智能助理，请问有什么可以帮您？";
    }
}
