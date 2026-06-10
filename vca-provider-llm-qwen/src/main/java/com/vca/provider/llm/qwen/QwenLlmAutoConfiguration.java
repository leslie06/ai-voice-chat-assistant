package com.vca.provider.llm.qwen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;

/**
 * 通义千问文本 LLM 自动装配。仅当 {@code vca.providers.llm.qwen.enabled=true} 时生效 ——
 * "多厂商可插拔"的开关: 加 jar + 开开关 = 接入, 不动任何已有代码。
 *
 * <p>通过 META-INF/spring/...AutoConfiguration.imports 注册, 不依赖被组件扫描到。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.providers.llm.qwen", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(QwenLlmProperties.class)
public class QwenLlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(QwenLlmAutoConfiguration.class);

    /**
     * 专用 WebClient。只设连接超时; 流式空闲超时交给 Provider 内的 Flux.timeout 处理,
     * 避免 reactor-netty 的 responseTimeout 误杀长流。连接池配空闲剔除避免复用死连接。
     */
    @Bean
    WebClient qwenLlmWebClient(QwenLlmProperties props) {
        ConnectionProvider pool = ConnectionProvider.builder("qwen-llm")
                .maxIdleTime(props.getMaxIdleTime())
                .maxLifeTime(props.getMaxLifeTime())
                .evictInBackground(props.getMaxIdleTime())
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) props.getConnectTimeout().toMillis())
                // 用 JDK 自带解析器(走系统 DNS), 绕开 Netty 异步 DNS: macOS 上缺原生库
                // netty-resolver-dns-native-macos 时, Netty 会回退去读 /etc/resolv.conf,
                // 而 macOS 真实 DNS 配置不在那里 → 解析 dashscope 域名失败。JDK 解析器无此问题。
                .resolver(DefaultAddressResolverGroup.INSTANCE);
        // 可选代理; DashScope 在国内一般直连。
        if (props.getProxy() != null && !props.getProxy().isBlank()) {
            URI p = URI.create(props.getProxy().trim());
            int port = p.getPort() > 0 ? p.getPort() : 7890;
            httpClient = httpClient.proxy(spec -> spec
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(p.getHost())
                    .port(port));
            log.info("Qwen LLM 走代理: {}:{}", p.getHost(), port);
        }
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    // 注意: 解析 SSE 用的 ObjectMapper 是本厂商内部工具, 直接内联 new, 不暴露成容器 Bean ——
    // 否则多个 LLM provider 模块各暴露一个 ObjectMapper, 会与宿主按类型注入处发生 Bean 歧义。
    @Bean
    QwenLlmProvider qwenLlmProvider(WebClient qwenLlmWebClient, QwenLlmProperties props) {
        return new QwenLlmProvider(qwenLlmWebClient, new ObjectMapper(), props);
    }
}
