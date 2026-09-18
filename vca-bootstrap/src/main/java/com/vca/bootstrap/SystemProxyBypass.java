package com.vca.bootstrap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 让阿里云域名绕开系统代理, 并在启动时把"这台机器有代理"这件事说清楚。
 *
 * <p>为什么需要: macOS 的 JVM <b>启动时会把操作系统的网络代理设置读成系统属性</b>
 * ({@code http.proxyHost} / {@code https.proxyHost} / {@code socksProxyHost}), 不需要任何 -D 参数。
 * 本机开着 Clash 这类工具的"系统代理"时, OkHttp 默认用 {@code ProxySelector.getDefault()},
 * 于是 DashScope 的识别/合成 WebSocket 也被送进代理。代理一关(或重启、切节点), 日志里就是一片
 * {@code Websocket failure Failed to connect to /127.0.0.1:7890} —— 电话里的表现是 AI 突然不说话,
 * 而报错信息里完全看不出跟代理有关。
 *
 * <p>阿里云的接口在国内直连最快, 走代理只多一跳、还平白多一个单点故障, 所以默认让它直连。
 * 确实需要让阿里云也走代理时, 加 {@code -Dvca.proxy.aliyun=true} 关掉本机制。
 *
 * <p><b>socks 也要一起排除</b>: JDK 的默认选择器是先看 http/https, 命中 nonProxyHosts 后<b>还会
 * 接着看 socks</b> —— 只排除 http 会被 socks 接住, 等于没排除。
 */
final class SystemProxyBypass {

    /** 阿里云(DashScope 识别/合成/向量, OSS 等)。逗号/竖线分隔在 JDK 里都按竖线解析, 这里用竖线。 */
    private static final String ALIYUN_HOSTS = "aliyuncs.com|*.aliyuncs.com|aliyun.com|*.aliyun.com";

    private SystemProxyBypass() {
    }

    /**
     * 必须在任何网络调用之前执行(即 Spring 启动之前)。只动系统属性, 不碰任何 bean。
     * 用 stdout 而非日志: 此时日志系统还没初始化。
     */
    static void apply() {
        String host = firstNonBlank(System.getProperty("https.proxyHost"), System.getProperty("http.proxyHost"));
        String port = firstNonBlank(System.getProperty("https.proxyPort"), System.getProperty("http.proxyPort"));
        String socksHost = System.getProperty("socksProxyHost");
        if (host == null && socksHost == null) {
            return;   // 没有代理, 什么都不用做
        }

        String where = host != null ? host + ":" + (port == null ? "80" : port)
                : socksHost + ":" + System.getProperty("socksProxyPort", "1080");

        if (Boolean.getBoolean("vca.proxy.aliyun")) {
            System.out.println("· 检测到系统代理 " + where + "; 按 -Dvca.proxy.aliyun=true 的要求, 阿里云也走代理");
            return;
        }

        appendNonProxyHosts("http.nonProxyHosts");
        appendNonProxyHosts("socksNonProxyHosts");
        System.out.println("· 检测到系统代理 " + where + " —— 已让阿里云域名直连(识别/合成/向量不受代理影响)");
        System.out.println("  其余境外厂商仍走这个代理; 代理关掉时它们会连不上。要让阿里云也走代理: -Dvca.proxy.aliyun=true");

        if (!reachable(host != null ? host : socksHost, port(host != null ? port : System.getProperty("socksProxyPort")))) {
            System.out.println("  ⚠ 这个代理端口现在连不上(没开/已退出)。境外厂商(OpenAI、Gemini 等)此刻不可用。");
        }
    }

    /** 保留 JVM 自己算出来的那份名单(localhost、内网段等), 只往后追加, 不覆盖。 */
    private static void appendNonProxyHosts(String property) {
        String current = System.getProperty(property, "").trim();
        if (current.contains("aliyuncs.com")) {
            return;
        }
        System.setProperty(property, current.isEmpty() ? ALIYUN_HOSTS : current + "|" + ALIYUN_HOSTS);
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int port(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException e) {
            return 80;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
