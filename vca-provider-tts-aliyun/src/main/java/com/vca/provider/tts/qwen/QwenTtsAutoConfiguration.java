package com.vca.provider.tts.qwen;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Qwen-TTS 自动装配。仅当 {@code vca.providers.tts.qwen.enabled=true} 时注册。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.providers.tts.qwen", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(QwenTtsProperties.class)
public class QwenTtsAutoConfiguration {

    @Bean
    QwenTtsProvider qwenTtsProvider(QwenTtsProperties props) {
        return new QwenTtsProvider(props);
    }
}
