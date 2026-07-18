package com.vca.store;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class OssAudioRecordingServiceTest {

    @Test
    void buildsValidMonoPcmWavHeader() {
        byte[] headerBytes = OssAudioRecordingService.wavHeader(12_345, 24_000);
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(new String(headerBytes, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(headerBytes, 8, 4)).isEqualTo("WAVE");
        assertThat(header.getInt(4)).isEqualTo(12_381);
        assertThat(header.getInt(24)).isEqualTo(24_000);
        assertThat(header.getShort(22)).isEqualTo((short) 1);
        assertThat(header.getShort(34)).isEqualTo((short) 16);
        assertThat(header.getInt(40)).isEqualTo(12_345);
    }

    @Test
    void uploadsLargeWavAsOrderedMultipartWithoutLocalFile() throws Exception {
        Map<Integer, byte[]> uploaded = new TreeMap<>();
        OSS oss = (OSS) Proxy.newProxyInstance(OSS.class.getClassLoader(), new Class<?>[]{OSS.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("initiateMultipartUpload")) {
                        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
                        result.setUploadId("upload-1");
                        return result;
                    }
                    if (method.getName().equals("uploadPart")) {
                        UploadPartRequest request = (UploadPartRequest) args[0];
                        try (InputStream in = request.getInputStream()) {
                            uploaded.put(request.getPartNumber(), in.readAllBytes());
                        }
                        UploadPartResult result = new UploadPartResult();
                        result.setPartNumber(request.getPartNumber());
                        result.setETag("etag-" + request.getPartNumber());
                        result.setPartSize(request.getPartSize());
                        return result;
                    }
                    return defaultValue(method.getReturnType());
                });

        int partSize = 100 * 1024;
        byte[] pcm = new byte[partSize * 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        try (OssAudioRecordingService.OssWavWriter writer =
                     new OssAudioRecordingService.OssWavWriter(
                             oss, "bucket", "recordings/test.wav", partSize, 24_000)) {
            writer.write(pcm, 24_000);
        }

        byte[] complete = uploaded.values().stream().reduce(new byte[0], (left, right) -> {
            byte[] joined = java.util.Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, joined, left.length, right.length);
            return joined;
        });
        assertThat(uploaded.keySet()).containsExactly(1, 2, 3);
        assertThat(complete).hasSize(44 + pcm.length);
        assertThat(java.util.Arrays.copyOfRange(complete, 44, complete.length)).isEqualTo(pcm);
        assertThat(ByteBuffer.wrap(complete).order(ByteOrder.LITTLE_ENDIAN).getInt(40)).isEqualTo(pcm.length);
    }

    @Test
    void buildsDialogueInUserAssistantOrderWithSilence() throws Exception {
        Map<String, byte[]> objects = new TreeMap<>();
        OSS oss = (OSS) Proxy.newProxyInstance(OSS.class.getClassLoader(), new Class<?>[]{OSS.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putObject") && args[0] instanceof PutObjectRequest request) {
                        try (InputStream in = request.getInputStream()) {
                            objects.put(request.getKey(), in.readAllBytes());
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
        OssAudioRecordingService.OssWavWriter wav = new OssAudioRecordingService.OssWavWriter(
                oss, "bucket", "conversation.wav", 100 * 1024, 24_000);
        OssAudioRecordingService.DialogueWriter dialogue = new OssAudioRecordingService.DialogueWriter(wav);

        dialogue.writeUser(new byte[16_000 / 10 * 2], 16_000);       // 用户 100ms，重采样到 24k
        dialogue.writeAssistant(new byte[24_000 / 10 * 2], 24_000);  // 先插 300ms，再写客服 100ms
        wav.close();

        byte[] object = objects.get("conversation.wav");
        int expectedPcmBytes = (2_400 + 7_200 + 2_400) * 2;
        assertThat(object).hasSize(44 + expectedPcmBytes);
        assertThat(ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(24_000);
        assertThat(ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN).getInt(40)).isEqualTo(expectedPcmBytes);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
