package com.vca.provider.tts.aliyun;

import com.alibaba.dashscope.audio.ttsv2.enrollment.Voice;
import com.alibaba.dashscope.audio.ttsv2.enrollment.VoiceEnrollmentService;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.spi.VoiceCloner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Locale;

/**
 * 阿里云百炼"声音复刻": 拿一段 10~20 秒的人声样本, 换回一个可直接当 voice 用的专属音色 id。
 *
 * <p><b>样本不落对象存储</b>。官方文档只演示了公网 URL, 但实测 Qwen-Audio 与 CosyVoice 的
 * create_voice 都接受 {@code data:audio/wav;base64,...} 内联音频(给坏数据报的是
 * "detect audio failed" 而非 "download audio failed", 说明确实在解码内联数据), 于是浏览器
 * 录下来的音频可以直接转手交给厂商, 省掉一整套 OSS 依赖。
 *
 * <p>返回的 id 实测形如 {@code qwen-audio-3.0-tts-flash-u1a-9528a83e439a428eb1b202e307f1eb24},
 * 即"目标模型名 + 前缀 + 32 位十六进制"。{@link AliyunTtsProperties#modelFor(String)} 正是靠
 * 这个前缀把复刻音色路由回它绑定的模型 —— 合成时的模型必须与创建时的 target_model 完全一致。
 */
public class VoiceCloneService implements VoiceCloner {

    private static final Logger log = LoggerFactory.getLogger(VoiceCloneService.class);

    /** 厂商要求: 前缀只能是数字和小写字母, 且少于 10 个字符。 */
    private static final int MAX_PREFIX = 9;

    private final AliyunTtsProperties props;

    public VoiceCloneService(AliyunTtsProperties props) {
        this.props = props;
    }

    /** 复刻用的目标模型: 跟着主线走, 这样复刻音色和系统音色的合成能力一致。 */
    @Override
    public String targetModel() {
        return props.getQwenAudioFlashModel();
    }

    /**
     * 创建音色。
     *
     * @param prefix 音色名前缀, 会被规整成合法形式(仅小写字母数字、≤9 字符)
     * @param wav    完整的 WAV 字节(16bit 单声道, ≥16kHz)
     * @return 厂商音色 id
     */
    @Override
    public String create(String prefix, byte[] wav) {
        String uri = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        String model = targetModel();
        try {
            Voice voice = service().createVoice(model, sanitize(prefix), uri);
            String id = voice == null ? null : voice.getVoiceId();
            if (id == null || id.isBlank()) {
                throw ProviderException.fatal(VendorType.ALIYUN, Capability.TTS,
                        "声音复刻未返回音色 id", null);
            }
            log.info("声音复刻成功: model={}, voiceId={}, 样本字节={}", model, id, wav.length);
            return id;
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw ProviderException.fatal(VendorType.ALIYUN, Capability.TTS,
                    "声音复刻失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除音色。厂商侧删除失败不抛异常 —— 调用方在这之后还要删本地记录, 不能因为
     * 云端已经没有这个音色(比如被一年未使用的清理规则收走了)就把本地记录也留下来。
     *
     * @return 云端是否确实删掉了
     */
    @Override
    public boolean delete(String voiceId) {
        try {
            service().deleteVoice(voiceId);
            return true;
        } catch (Exception e) {
            log.warn("删除云端音色失败(本地记录仍会清掉): voiceId={}, err={}", voiceId, e.toString());
            return false;
        }
    }

    private VoiceEnrollmentService service() {
        return new VoiceEnrollmentService(props.getApiKey());
    }

    /** 前缀只保留小写字母数字并截断; 全被过滤掉时兜一个固定值, 不让厂商因为空前缀报错。 */
    static String sanitize(String prefix) {
        if (prefix == null) {
            return "vca";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : prefix.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            }
            if (sb.length() == MAX_PREFIX) {
                break;
            }
        }
        return sb.isEmpty() ? "vca" : sb.toString();
    }
}
