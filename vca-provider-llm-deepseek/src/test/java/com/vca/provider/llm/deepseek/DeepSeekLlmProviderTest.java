package com.vca.provider.llm.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.Message;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekLlmProviderTest {

    private MockWebServer server;
    private DeepSeekLlmProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        DeepSeekProperties props = new DeepSeekProperties();
        props.setEnabled(true);
        props.setBaseUrl(server.url("/").toString());
        props.setDefaultModel("deepseek-chat");
        props.setKeys(List.of("sk-test-key"));

        WebClient webClient = WebClient.builder().baseUrl(props.getBaseUrl()).build();
        provider = new DeepSeekLlmProvider(webClient, new ObjectMapper(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void streamsTokensAndStopsAtDone() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"content":"你好"}}]}

                data: {"choices":[{"delta":{"content":"，世界"}}]}

                data: {"choices":[{"delta":{}}]}

                data: [DONE]

                """;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream;charset=UTF-8")
                .setBody(sse));

        StepVerifier.create(provider.chatStream(
                        List.of(Message.system("你是助手"), Message.user("你好")),
                        LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat")))
                .expectNext("你好")
                .expectNext("，世界")
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-test-key");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"stream\":true");
        assertThat(body).contains("\"model\":\"deepseek-chat\"");
        assertThat(body).contains("你好");
    }

    @Test
    void maps429ToQuotaException() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("rate limit exceeded"));

        StepVerifier.create(provider.chatStream(
                        List.of(Message.user("hi")),
                        LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat")))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ProviderException.class);
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.isQuotaExceeded()).isTrue();
                    assertThat(pe.vendor()).isEqualTo(VendorType.DEEPSEEK);
                })
                .verify();
    }
}
