package com.vca.web.music;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.green20220302.models.VoiceModerationRequest;
import com.aliyun.green20220302.models.VoiceModerationResponse;
import com.aliyun.green20220302.models.VoiceModerationResponseBody;
import com.aliyun.green20220302.models.VoiceModerationResultRequest;
import com.aliyun.green20220302.models.VoiceModerationResultResponse;
import com.aliyun.green20220302.models.VoiceModerationResultResponseBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.web.WebProperties;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 阿里云内容安全 2.0：歌曲元数据文本审核 + OSS 音频异步审核。 */
public final class AliyunMusicModerationClient {

    private static final Set<String> FORBIDDEN_LABELS = Set.of(
            "sexual_content", "sexuality", "sexual_sounds",
            "political_content", "regional", "violence");

    private final Client client;
    private final ObjectMapper json;
    private final String bucket;
    private final String ossRegion;
    private final String textService;
    private final String audioService;

    public AliyunMusicModerationClient(
            WebProperties props, ObjectMapper json, String bucket) throws Exception {
        Config config = new Config()
                .setAccessKeyId(props.getMusicModerationAccessKeyId())
                .setAccessKeySecret(props.getMusicModerationAccessKeySecret())
                .setRegionId(props.getMusicModerationRegion())
                .setEndpoint(props.getMusicModerationEndpoint())
                .setConnectTimeout(5000)
                .setReadTimeout(10000);
        this.client = new Client(config);
        this.json = json;
        this.bucket = bucket;
        this.ossRegion = props.getMusicModerationRegion();
        this.textService = props.getMusicModerationTextService();
        this.audioService = props.getMusicModerationAudioService();
    }

    TextCheck checkText(String title, String artist) throws Exception {
        TextModerationRequest request = new TextModerationRequest()
                .setService(textService)
                .setServiceParameters(json.writeValueAsString(Map.of(
                        "content", "歌名：" + title + "；歌手：" + artist)));
        RuntimeOptions runtime = new RuntimeOptions();
        runtime.connectTimeout = 5000;
        runtime.readTimeout = 10000;
        TextModerationResponse response = client.textModerationWithOptions(request, runtime);
        TextModerationResponseBody body = response == null ? null : response.getBody();
        if (response == null || response.getStatusCode() != 200
                || body == null || body.getCode() == null || body.getCode() != 200
                || body.getData() == null) {
            throw new IllegalStateException("文本审核调用失败: " + error(response == null ? null : body));
        }
        TextModerationResponseBody.TextModerationResponseBodyData data = body.getData();
        String labels = nullToEmpty(data.getLabels());
        return new TextCheck(hasForbiddenLabel(labels), labels, nullToEmpty(data.getReason()));
    }

    String submitAudio(String audioObjectKey) throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("ossBucketName", bucket);
        parameters.put("ossObjectName", audioObjectKey);
        parameters.put("ossRegionId", ossRegion);
        VoiceModerationRequest request = new VoiceModerationRequest()
                .setService(audioService)
                .setServiceParameters(json.writeValueAsString(parameters));
        VoiceModerationResponse response = client.voiceModeration(request);
        VoiceModerationResponseBody body = response == null ? null : response.getBody();
        if (response == null || response.getStatusCode() != 200
                || body == null || body.getCode() == null || body.getCode() != 200
                || body.getData() == null || body.getData().getTaskId() == null) {
            throw new IllegalStateException("音频审核提交失败: " + error(body));
        }
        return body.getData().getTaskId();
    }

    AudioCheck queryAudio(String taskId) throws Exception {
        VoiceModerationResultRequest request = new VoiceModerationResultRequest()
                .setService(audioService)
                .setServiceParameters(json.writeValueAsString(Map.of("taskId", taskId)));
        VoiceModerationResultResponse response = client.voiceModerationResult(request);
        VoiceModerationResultResponseBody body = response == null ? null : response.getBody();
        if (response == null || response.getStatusCode() != 200 || body == null) {
            throw new IllegalStateException("音频审核查询失败: HTTP "
                    + (response == null ? "null" : response.getStatusCode()));
        }
        if (body.getCode() == null || body.getCode() != 200) {
            // 异步任务尚未完成时服务端可能暂时返回非 200 业务码，留在 reviewing 下次再查。
            return new AudioCheck(false, false, "", "", nullToEmpty(body.getMessage()));
        }
        VoiceModerationResultResponseBody.VoiceModerationResultResponseBodyData data = body.getData();
        if (data == null || data.getRiskLevel() == null || data.getRiskLevel().isBlank()) {
            return new AudioCheck(false, false, "", "", "");
        }
        String risk = data.getRiskLevel().toLowerCase(Locale.ROOT);
        String details = json.writeValueAsString(data.getSliceDetails());
        boolean blocked = "high".equals(risk) || "medium".equals(risk)
                || hasForbiddenLabel(details);
        return new AudioCheck(true, blocked, risk, details, "");
    }

    private static boolean hasForbiddenLabel(String value) {
        String normalized = nullToEmpty(value).toLowerCase(Locale.ROOT);
        return FORBIDDEN_LABELS.stream().anyMatch(normalized::contains);
    }

    private static String error(VoiceModerationResponseBody body) {
        return body == null ? "无响应" : body.getCode() + " " + body.getMessage();
    }

    private static String error(TextModerationResponseBody body) {
        return body == null ? "无响应" : body.getCode() + " " + body.getMessage();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record TextCheck(boolean blocked, String labels, String reason) {}
    record AudioCheck(boolean completed, boolean blocked, String riskLevel,
                      String details, String reason) {}
}
