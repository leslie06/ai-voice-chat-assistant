package com.vca.provider.asr.aliyun;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 阿里云 ASR 自动装配。仅当 {@code vca.providers.asr.aliyun.enabled=true} 时注册。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.providers.asr.aliyun", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AliyunAsrProperties.class)
public class AliyunAsrAutoConfiguration {

    @Bean
    AliyunAsrProvider aliyunAsrProvider(AliyunAsrProperties props) {
        return new AliyunAsrProvider(props);
    }
}
