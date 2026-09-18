package com.vca.telephony.summary;

import com.vca.orchestrator.call.CallSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把小结 POST 到一个 webhook。
 *
 * <p><b>为什么是 webhook 而不是短信/微信</b>: 个人号发微信要公众号资质, 短信要短信资质, 而企业微信和钉钉的
 * 群机器人只要一个 URL、当天就能用, 小诊所把机器人拉进店长群即可。自建后台也能收同一个请求。
 *
 * <p>报文同时满足两种消费者: 顶层是企业微信/钉钉机器人认的 {@code msgtype=text} 文本消息(它们忽略多余字段),
 * 另外挂一个 {@code call} 对象给自建后台用结构化字段。
 */
public final class WebhookCallNotifier implements CallNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookCallNotifier.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;
    private final String url;

    public WebhookCallNotifier(String url) {
        this.url = url;
        this.http = WebClient.builder().build();
    }

    @Override
    public void notify(CallSummary s) {
        if (s == null || url == null || url.isBlank()) {
            return;
        }
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("callId", s.callId());
        call.put("ownerId", s.ownerId());
        call.put("peerNumber", s.peerNumber());
        call.put("calledNumber", s.calledNumber());
        call.put("durationSec", s.durationSec());
        call.put("turns", s.turns());
        call.put("summary", s.summary());
        call.put("intent", s.intent());
        call.put("followUp", s.followUp());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", Map.of("content", text(s)));
        body.put("call", call);

        try {
            http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    // 推送失败不重要到要影响任何东西: 小结已落库, 商家后台还能看到
                    .doOnError(e -> log.warn("[{}] 小结推送失败: {}", s.callId(), e.toString()))
                    .onErrorComplete()
                    .subscribe();
        } catch (RuntimeException e) {
            log.warn("[{}] 小结推送失败: {}", s.callId(), e.toString());
        }
    }

    /** 群里一眼能看完的样子 */
    static String text(CallSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("【来电小结】意向 ").append(s.intent() == null ? "?" : s.intent()).append('\n');
        sb.append("来电: ").append(s.peerNumber() == null ? "未知号码" : s.peerNumber());
        sb.append("  时长: ").append(s.durationSec()).append(" 秒\n");
        sb.append(s.summary() == null ? "(无摘要)" : s.summary());
        if (s.followUp() != null && !s.followUp().isBlank()) {
            sb.append("\n待跟进: ").append(s.followUp());
        }
        return sb.toString();
    }
}
