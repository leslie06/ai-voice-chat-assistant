package com.vca.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 系统代理旁路。事故背景: 本机 Clash 的"系统代理"被 JVM 读成 {@code http.proxyHost},
 * DashScope 的识别 WebSocket 因此走了代理; 代理一关, 电话里的 AI 就不说话了。
 */
class SystemProxyBypassTest {

    private static final String[] TOUCHED = {
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort",
            "socksProxyHost", "socksProxyPort", "http.nonProxyHosts", "socksNonProxyHosts",
            "vca.proxy.aliyun"};

    private final String[] saved = new String[TOUCHED.length];

    @BeforeEach
    void save() {
        for (int i = 0; i < TOUCHED.length; i++) {
            saved[i] = System.getProperty(TOUCHED[i]);
            System.clearProperty(TOUCHED[i]);
        }
    }

    @AfterEach
    void restore() {
        for (int i = 0; i < TOUCHED.length; i++) {
            if (saved[i] == null) {
                System.clearProperty(TOUCHED[i]);
            } else {
                System.setProperty(TOUCHED[i], saved[i]);
            }
        }
    }

    /** 该 URL 是否直连(名单里没有任何代理) */
    private static boolean direct(String url) {
        List<Proxy> chosen = ProxySelector.getDefault().select(URI.create(url));
        return chosen.stream().allMatch(p -> p.type() == Proxy.Type.DIRECT);
    }

    private static void pretendSystemProxy() {
        // macOS 上 JVM 启动时就是这么写进来的: http/https/socks 三份全有
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "7890");
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7890");
        System.setProperty("socksProxyHost", "127.0.0.1");
        System.setProperty("socksProxyPort", "7890");
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]");
    }

    @Test
    void aliyunGoesDirectWhileOthersKeepUsingTheProxy() {
        pretendSystemProxy();
        assertFalse(direct("https://dashscope.aliyuncs.com/api-ws/v1/inference"),
                "旁路之前, 阿里云也被送进代理 —— 这就是事故现场");

        SystemProxyBypass.apply();

        assertTrue(direct("https://dashscope.aliyuncs.com/api-ws/v1/inference"), "阿里云应直连");
        assertFalse(direct("https://api.openai.com/v1/chat/completions"), "境外厂商应照旧走代理");
    }

    @Test
    void socksIsExcludedToo() {
        pretendSystemProxy();
        System.clearProperty("http.proxyHost");
        System.clearProperty("https.proxyHost");   // 只剩 socks: JDK 会用它接住 http 排除掉的请求

        SystemProxyBypass.apply();

        assertTrue(direct("https://dashscope.aliyuncs.com/api-ws/v1/inference"),
                "只排除 http 的话会被 socks 接住, 等于没排除");
    }

    @Test
    void keepsJvmComputedEntriesAndIsIdempotent() {
        pretendSystemProxy();

        SystemProxyBypass.apply();
        SystemProxyBypass.apply();   // 重复执行不应把名单撑成两份

        String list = System.getProperty("http.nonProxyHosts");
        assertTrue(list.contains("localhost") && list.contains("127.*"), "JVM 自己算出的条目要保留: " + list);
        assertEquals(1, java.util.Arrays.stream(list.split("\\|")).filter("aliyuncs.com"::equals).count(), list);
    }

    @Test
    void optOutKeepsAliyunOnTheProxy() {
        pretendSystemProxy();
        System.setProperty("vca.proxy.aliyun", "true");

        SystemProxyBypass.apply();

        assertFalse(direct("https://dashscope.aliyuncs.com/api-ws/v1/inference"));
    }

    @Test
    void doesNothingWhenThereIsNoProxy() {
        SystemProxyBypass.apply();
        assertNull(System.getProperty("http.nonProxyHosts"));
    }
}
