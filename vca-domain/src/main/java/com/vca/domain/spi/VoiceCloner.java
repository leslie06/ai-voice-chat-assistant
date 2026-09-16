package com.vca.domain.spi;

/**
 * 声音复刻: 用一段人声样本换一个专属音色 id, 之后就能当普通 voice 用。
 *
 * <p>独立于 {@link TtsProvider} 的原因是生命周期完全不同 —— 复刻是一次性的账号级操作
 * (音色建好就长期留在厂商那边), 合成是每句都要走的实时链路。
 */
public interface VoiceCloner {

    /**
     * 复刻出的音色绑定的合成模型。厂商要求"创建时的 target_model 必须与合成时的模型完全一致",
     * 调用方需要把它存下来。
     */
    String targetModel();

    /**
     * @param prefix 音色名前缀(实现会规整成厂商要求的形式)
     * @param wav    完整 WAV 字节, 16bit 单声道
     * @return 厂商音色 id
     */
    String create(String prefix, byte[] wav);

    /** 删除厂商侧音色; 返回云端是否确实删掉了(删不掉也不抛异常, 本地记录照样要清)。 */
    boolean delete(String voiceId);
}
