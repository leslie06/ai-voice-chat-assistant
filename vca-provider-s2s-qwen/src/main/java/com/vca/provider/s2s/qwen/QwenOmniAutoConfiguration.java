package com.vca.provider.s2s.qwen;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Qwen-Omni 端到端语音自动装配。仅当 {@code vca.providers.s2s.qwen.enabled=true} 时注册,
 * 注册后该 S2sProvider 进入治理注册表, 会话以 {@code vca.web.mode=s2s} 切到端到端模式即可用。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.providers.s2s.qwen", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(QwenOmniProperties.class)
public class QwenOmniAutoConfiguration {

    @Bean
    QwenOmniS2sProvider qwenOmniS2sProvider(QwenOmniProperties props) {
        return new QwenOmniS2sProvider(props);
    }
}
