package com.vca.provider.tts.aliyun;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 阿里云 TTS 自动装配。仅当 {@code vca.providers.tts.aliyun.enabled=true} 时注册。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.providers.tts.aliyun", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AliyunTtsProperties.class)
public class AliyunTtsAutoConfiguration {

    @Bean
    AliyunTtsProvider aliyunTtsProvider(AliyunTtsProperties props) {
        return new AliyunTtsProvider(props);
    }

    /** 声音复刻。与合成共用同一份 api-key 与模型配置, 所以跟着 TTS provider 一起装配。 */
    @Bean
    VoiceCloneService aliyunVoiceCloneService(AliyunTtsProperties props) {
        return new VoiceCloneService(props);
    }
}
