package com.vca.provider.llm.deepseek;

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
 * DeepSeek 自动装配。仅当 {@code vca.providers.llm.deepseek.enabled=true} 时生效,
 * 这就是"多厂商可插拔"的开关: 加 jar + 开开关 = 接入, 不动任何已有代码。
 *
 * <p>通过 META-INF/spring/...AutoConfiguration.imports 注册, 不依赖被组件扫描到。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "vca.providers.llm.deepseek", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAutoConfiguration.class);

    /**
     * 专用 WebClient。只设连接超时; 流式空闲超时交给 Provider 内的 Flux.timeout 处理,
     * 避免 reactor-netty 的 responseTimeout 误杀长流。
     *
     * <p>关键: 连接池配置空闲剔除(maxIdleTime)+ 后台清扫, 在对端关闭 keep-alive 死连接
     * 之前先把它从池里剔除, 避免复用死连接导致 "Connection prematurely closed BEFORE response"。
     */
    @Bean
    WebClient deepSeekWebClient(DeepSeekProperties props) {
        ConnectionProvider pool = ConnectionProvider.builder("deepseek")
                .maxIdleTime(props.getMaxIdleTime())
                .maxLifeTime(props.getMaxLifeTime())
                .evictInBackground(props.getMaxIdleTime())
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) props.getConnectTimeout().toMillis())
                // 用 JDK 自带解析器(走系统 DNS), 绕开 Netty 异步 DNS: macOS 上缺原生库时 Netty 回退
                // 读 /etc/resolv.conf 会解析不到域名(关代理直连 api.deepseek.com 时才会触发)。
                .resolver(DefaultAddressResolverGroup.INSTANCE);
        // 可选: 仅给本客户端挂 HTTP 代理(本机有代理时直连会 TLS 握手超时)。其它厂商不受影响。
        if (props.getProxy() != null && !props.getProxy().isBlank()) {
            URI p = URI.create(props.getProxy().trim());
            int port = p.getPort() > 0 ? p.getPort() : 7890;
            httpClient = httpClient.proxy(spec -> spec
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(p.getHost())
                    .port(port));
            log.info("DeepSeek 走代理: {}:{}", p.getHost(), port);
        } else {
            log.info("DeepSeek 直连(未配置代理); 本机若开了 Clash 等代理可能导致 TLS 握手超时");
        }
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    // 注意: 解析 SSE 用的 ObjectMapper 是本厂商内部工具, 直接内联 new, 不暴露成容器 Bean ——
    // 否则多个 LLM provider 模块各暴露一个 ObjectMapper, 会与宿主按类型注入处发生 Bean 歧义。
    @Bean
    DeepSeekLlmProvider deepSeekLlmProvider(WebClient deepSeekWebClient, DeepSeekProperties props) {
        return new DeepSeekLlmProvider(deepSeekWebClient, new ObjectMapper(), props);
    }
}
