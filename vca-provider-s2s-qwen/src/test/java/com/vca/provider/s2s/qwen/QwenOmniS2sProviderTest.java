package com.vca.provider.s2s.qwen;

import com.google.gson.JsonObject;
import com.vca.domain.enums.VendorType;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线单测: 覆盖事件字段提取、默认回退、以及音频 base64 解码这几处确定性逻辑。
 * 真实的端到端语音往返需要 DASHSCOPE_API_KEY + Omni 实时模型权限, 不在单测范围内。
 */
class QwenOmniS2sProviderTest {

    private final QwenOmniS2sProvider provider = new QwenOmniS2sProvider(new QwenOmniProperties());

    @Test
    void vendorIsQwen() {
        assertThat(provider.vendor()).isEqualTo(VendorType.QWEN);
    }

    @Test
    void valueFallsBackWhenBlank() {
        assertThat(QwenOmniS2sProvider.value("Ethan", "Chelsie")).isEqualTo("Ethan");
        assertThat(QwenOmniS2sProvider.value(null, "Chelsie")).isEqualTo("Chelsie");
        assertThat(QwenOmniS2sProvider.value("   ", "Chelsie")).isEqualTo("Chelsie");
    }

    @Test
    void optStringHandlesMissingAndNull() {
        JsonObject obj = new JsonObject();
        assertThat(QwenOmniS2sProvider.optString(obj, "delta")).isNull();
        obj.add("nullable", com.google.gson.JsonNull.INSTANCE);
        assertThat(QwenOmniS2sProvider.optString(obj, "nullable")).isNull();
        obj.addProperty("delta", "hi");
        assertThat(QwenOmniS2sProvider.optString(obj, "delta")).isEqualTo("hi");
    }

    /** 模拟 response.audio.delta 里的 base64 PCM: 提取字段后能正确还原原始字节。 */
    @Test
    void audioDeltaBase64RoundTrips() {
        byte[] pcm = {0, 1, 2, 3, 64, -1, -128, 127};
        JsonObject event = new JsonObject();
        event.addProperty("type", "response.audio.delta");
        event.addProperty("delta", Base64.getEncoder().encodeToString(pcm));

        String b64 = QwenOmniS2sProvider.optString(event, "delta");
        assertThat(Base64.getDecoder().decode(b64)).containsExactly(pcm);
    }
}
