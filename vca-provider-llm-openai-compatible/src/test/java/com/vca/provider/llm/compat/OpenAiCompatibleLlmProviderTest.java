package com.vca.provider.llm.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.model.LlmConfig;
import com.vca.domain.model.LlmEvent;
import com.vca.domain.model.Message;
import com.vca.domain.model.ToolCall;
import com.vca.domain.model.ToolSpec;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmProviderTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void streamsTokensAndBuildsKimiCompatibleBody() throws Exception {
        OpenAiCompatibleLlmProperties.Client props = client(VendorType.MOONSHOT, "Kimi");
        props.setDefaultModel("kimi-k3");
        props.setMaxTokensField("");
        props.setIncludeTemperature(false);
        OpenAiCompatibleLlmProvider provider = provider("kimi", props);

        String sse = """
                data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"content":"你好"}}]}

                data: {"choices":[{"delta":{"content":"，我是 Kimi"}}]}

                data: [DONE]

                """;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream;charset=UTF-8")
                .setBody(sse));

        StepVerifier.create(provider.chatStream(
                        List.of(
                                Message.system("你是 Kimi，由 Moonshot AI 提供的人工智能助手。"),
                                Message.user("你好，请介绍一下你自己")),
                        LlmConfig.defaults(VendorType.MOONSHOT, "kimi-k3")))
                .expectNext("你好")
                .expectNext("，我是 Kimi")
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-test-key");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"kimi-k3\"");
        assertThat(body).contains("\"role\":\"system\"");
        assertThat(body).contains("你是 Kimi，由 Moonshot AI 提供的人工智能助手。");
        assertThat(body).contains("\"stream\":true");
        assertThat(body).doesNotContain("chat_template_kwargs");
        assertThat(body).doesNotContain("temperature");
        assertThat(body).doesNotContain("max_tokens");
        assertThat(body).doesNotContain("max_completion_tokens");
    }

    @Test
    void canUseMaxCompletionTokensFieldWhenConfigured() throws Exception {
        OpenAiCompatibleLlmProperties.Client props = client(VendorType.MOONSHOT, "Kimi");
        props.setMaxTokensField("max_completion_tokens");
        OpenAiCompatibleLlmProvider provider = provider("kimi", props);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream;charset=UTF-8")
                .setBody("data: [DONE]\n\n"));

        StepVerifier.create(provider.chatStream(
                        List.of(Message.user("hi")),
                        new LlmConfig(VendorType.MOONSHOT, "kimi-k3", "", 0.7, 321)))
                .verifyComplete();

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"max_completion_tokens\":321");
    }

    @Test
    void maps429ToQuotaException() {
        OpenAiCompatibleLlmProperties.Client props = client(VendorType.DEEPSEEK, "DeepSeek");
        OpenAiCompatibleLlmProvider provider = provider("deepseek", props);
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

    @Test
    void assemblesStreamedToolCallsAndSendsToolsInBody() throws Exception {
        OpenAiCompatibleLlmProperties.Client props = client(VendorType.DEEPSEEK, "DeepSeek");
        OpenAiCompatibleLlmProvider provider = provider("deepseek", props);

        // tool_calls 分片: 首块带 id+name, 后续块流式拼 arguments; content 为 JSON null
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"杭州\\"}"}}]}}]}

                data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}

                data: [DONE]

                """;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream;charset=UTF-8")
                .setBody(sse));

        ToolSpec weather = new ToolSpec("get_weather", "查天气",
                Map.of("type", "object", "properties",
                        Map.of("city", Map.of("type", "string"))));

        StepVerifier.create(provider.chat(
                        List.of(Message.user("杭州天气")),
                        LlmConfig.defaults(VendorType.DEEPSEEK, "deepseek-chat"),
                        List.of(weather)))
                .assertNext(ev -> {
                    assertThat(ev).isInstanceOf(LlmEvent.ToolCalls.class);
                    List<ToolCall> calls = ((LlmEvent.ToolCalls) ev).calls();
                    assertThat(calls).hasSize(1);
                    assertThat(calls.get(0).id()).isEqualTo("call_abc");
                    assertThat(calls.get(0).name()).isEqualTo("get_weather");
                    assertThat(calls.get(0).arguments()).isEqualTo("{\"city\":\"杭州\"}");
                })
                .verifyComplete();

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"tools\":");
        assertThat(body).contains("\"name\":\"get_weather\"");
        assertThat(body).contains("\"tool_choice\":\"auto\"");
    }

    @Test
    void buildsMultimodalContentForImageMessage() throws Exception {
        OpenAiCompatibleLlmProperties.Client props = client(VendorType.QWEN, "Qwen");
        OpenAiCompatibleLlmProvider provider = provider("qwen", props);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream;charset=UTF-8")
                .setBody("data: [DONE]\n\n"));

        StepVerifier.create(provider.chatStream(
                        List.of(Message.user("图里有什么", "data:image/jpeg;base64,QUJD"),
                                Message.assistant("一只猫"),
                                Message.user("什么品种")),
                        LlmConfig.defaults(VendorType.QWEN, "qwen-vl-plus")))
                .verifyComplete();

        String body = server.takeRequest().getBody().readUtf8();
        // 带图消息: content 为多模态数组(image_url + text; Map.of 键序不定, 只断言各片段)
        assertThat(body).contains("\"url\":\"data:image/jpeg;base64,QUJD\"");
        assertThat(body).contains("\"type\":\"image_url\"");
        assertThat(body).contains("\"text\":\"图里有什么\"");
        // 纯文本消息不受影响: content 仍是字符串
        assertThat(body).contains("\"content\":\"什么品种\"");
    }

    private OpenAiCompatibleLlmProvider provider(String clientId, OpenAiCompatibleLlmProperties.Client props) {
        WebClient webClient = WebClient.builder().baseUrl(props.getBaseUrl()).build();
        return new OpenAiCompatibleLlmProvider(clientId, webClient, new ObjectMapper(), props);
    }

    private OpenAiCompatibleLlmProperties.Client client(VendorType vendor, String name) {
        OpenAiCompatibleLlmProperties.Client props = new OpenAiCompatibleLlmProperties.Client();
        props.setEnabled(true);
        props.setVendor(vendor);
        props.setName(name);
        props.setBaseUrl(server.url("/").toString());
        props.setDefaultModel("test-model");
        props.setKeys(List.of("sk-test-key"));
        props.setExtraBody(new LinkedHashMap<>());
        return props;
    }
}
