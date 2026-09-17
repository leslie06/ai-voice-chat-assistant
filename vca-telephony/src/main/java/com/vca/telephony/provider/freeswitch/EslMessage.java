package com.vca.telephony.provider.freeswitch;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FreeSWITCH 事件套接字(ESL)的一条报文。纯数据 + 编解码, 不碰连接状态, 便于单测直接喂字节。
 *
 * <p>线上格式和 HTTP 头很像:
 * <pre>
 *   Content-Length: 512            ← 有这一行才有正文, 长度按<b>字节</b>算
 *   Content-Type: text/event-plain
 *                                  ← 空行结束头部
 *   Event-Name: DTMF               ← 正文(这里正文本身又是一段头部, 即事件)
 *   DTMF-Digit: 5
 * </pre>
 *
 * <p><b>三个容易踩的点</b>:
 * <ol>
 *   <li>事件里的值是 URL 编码的(空格是 {@code %20}, 中文是 UTF-8 的 {@code %E4..})。
 *       <b>不能用 {@link java.net.URLDecoder}</b>: 它把 {@code +} 当空格, 而 FreeSWITCH 编码时
 *       {@code +} 会原样保留(例如号码 {@code +8613800138000}), 解出来就少了加号。</li>
 *   <li>Content-Length 是字节数, 正文可能含中文, 所以全程按字节读, 不能先套 Reader。</li>
 *   <li>{@code connect} 的应答没有 Content-Length, 通道数据直接平铺在应答头部里(同样 URL 编码)。</li>
 * </ol>
 */
public final class EslMessage {

    public static final String CT_REPLY = "command/reply";
    public static final String CT_API = "api/response";
    public static final String CT_EVENT_PLAIN = "text/event-plain";
    public static final String CT_DISCONNECT = "text/disconnect-notice";
    public static final String CT_AUTH_REQUEST = "auth/request";

    /** 单行上限: 防止对端(或串错端口的别的服务)发来没有换行的垃圾把内存吃光 */
    private static final int MAX_LINE_BYTES = 64 * 1024;
    /** 正文上限: 事件正文正常是几 KB */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final Map<String, String> headers;
    private final String body;

    public EslMessage(Map<String, String> headers, String body) {
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
    }

    public String get(String name) {
        return headers.get(name);
    }

    public String getOrDefault(String name, String fallback) {
        String v = headers.get(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String body() {
        return body;
    }

    public String contentType() {
        return getOrDefault("Content-Type", "");
    }

    /** 命令应答文本(已去掉尾部换行)。{@code connect} 的应答里它是 {@code "+OK\n"}。 */
    public String replyText() {
        return getOrDefault("Reply-Text", "").strip();
    }

    public boolean isOk() {
        return replyText().startsWith("+OK");
    }

    /** 把 {@code text/event-plain} 的正文解析成事件。事件自己也可能带正文(如 BACKGROUND_JOB 的结果)。 */
    public EslMessage event() {
        return parseBlock(body);
    }

    /** 事件名; 不是事件时返回空串 */
    public String eventName() {
        return getOrDefault("Event-Name", "");
    }

    // ---- 读 ----

    /**
     * 从流里读一条报文(阻塞)。
     *
     * @return null 表示对端正常关闭(报文之间的干净 EOF)
     * @throws EOFException 报文读到一半断了
     */
    public static EslMessage read(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null) {
                if (headers.isEmpty()) {
                    return null;
                }
                throw new EOFException("ESL 报文头不完整");
            }
            if (line.isEmpty()) {
                if (headers.isEmpty()) {
                    continue;   // 报文之间可能夹着多余的空行
                }
                break;
            }
            putHeader(headers, line);
        }
        String body = "";
        String len = headers.get("Content-Length");
        if (len != null) {
            int n = parseLength(len);
            body = new String(readExactly(in, n), StandardCharsets.UTF_8);
        }
        return new EslMessage(headers, body);
    }

    /**
     * 解析一段"头部 + 可选正文"的文本块(事件正文就是这种格式)。
     * 头部里若有 Content-Length, 空行之后按字节截取正文。
     */
    public static EslMessage parseBlock(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return new EslMessage(headers, "");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < bytes.length) {
            int nl = indexOf(bytes, (byte) '\n', pos);
            int end = nl < 0 ? bytes.length : nl;
            String line = new String(bytes, pos, end - pos, StandardCharsets.UTF_8);
            pos = nl < 0 ? bytes.length : nl + 1;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                break;
            }
            putHeader(headers, line);
        }
        String body = "";
        String len = headers.get("Content-Length");
        if (len != null && pos < bytes.length) {
            int n = Math.min(parseLengthLenient(len), bytes.length - pos);
            body = new String(bytes, pos, n, StandardCharsets.UTF_8);
        }
        return new EslMessage(headers, body);
    }

    private static void putHeader(Map<String, String> headers, String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return;   // 不是 "名: 值" 的行(如 disconnect-notice 正文里的欢送语), 忽略
        }
        String name = line.substring(0, colon).strip();
        String value = line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        headers.put(name, percentDecode(value));
    }

    /**
     * 只解 {@code %XX}, 不把 {@code +} 当空格(见类注释)。非法转义原样保留 ——
     * 解码失败不该让一整条事件丢掉。
     */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '%' && i + 2 < raw.length) {
                int hi = Character.digit(raw[i + 1], 16);
                int lo = Character.digit(raw[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(b);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- 写 ----

    /**
     * 组一条命令: 首行 + 若干 "名: 值" 头 + 结束空行。
     *
     * <p><b>这里是注入防线</b>: ESL 是换行分隔的行协议, 任何一段里混进换行, 就能在同一条连接上
     * 多塞一条任意命令(挂断别人的通话、originate 到任意号码)。所以带换行的输入直接拒绝,
     * 不做"清洗后放行"。上层对号码另有白名单, 这里是最后一道。
     */
    public static byte[] command(String line, String... headerPairs) {
        if (headerPairs.length % 2 != 0) {
            throw new IllegalArgumentException("头部必须成对: 名, 值");
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(requireSingleLine(line)).append('\n');
        for (int i = 0; i < headerPairs.length; i += 2) {
            sb.append(requireSingleLine(headerPairs[i])).append(": ")
                    .append(requireSingleLine(headerPairs[i + 1])).append('\n');
        }
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String requireSingleLine(String s) {
        if (s == null) {
            throw new IllegalArgumentException("ESL 命令字段不能为空");
        }
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("ESL 命令字段含换行, 拒绝发送(防命令注入)");
        }
        return s;
    }

    // ---- 字节级工具 ----

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(80);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
            }
            if (b == '\n') {
                String line = buf.toString(StandardCharsets.UTF_8);
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            if (buf.size() >= MAX_LINE_BYTES) {
                throw new IOException("ESL 报文单行超长, 对端可能不是 FreeSWITCH");
            }
            buf.write(b);
        }
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] data = in.readNBytes(n);
        if (data.length < n) {
            throw new EOFException("ESL 正文不完整: 期望 " + n + " 字节, 实得 " + data.length);
        }
        return data;
    }

    private static int parseLength(String raw) throws IOException {
        try {
            int n = Integer.parseInt(raw.strip());
            if (n < 0 || n > MAX_BODY_BYTES) {
                throw new IOException("ESL Content-Length 越界: " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IOException("ESL Content-Length 非法: " + raw, e);
        }
    }

    private static int parseLengthLenient(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int indexOf(byte[] bytes, byte target, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "EslMessage" + headers + (body.isEmpty() ? "" : " +body(" + body.length() + ")");
    }
}
