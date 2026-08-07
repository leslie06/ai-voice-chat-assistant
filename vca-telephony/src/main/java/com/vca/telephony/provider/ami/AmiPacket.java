package com.vca.telephony.provider.ami;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 AMI（Asterisk Manager Interface）报文。
 *
 * <p>协议是纯文本行: 每行 {@code Key: Value\r\n}, 空行结束一个报文。同一个 key 可以重复
 * (最典型的是 {@code Variable:}, 一次 Originate 要带好几个), 所以内部用有序列表而不是 Map。
 *
 * <p>纯数据结构, 不碰 IO —— 和 {@code AudioSocketCodec} 一样, 协议层可以先于 Asterisk 环境验完。
 */
public final class AmiPacket {

    private static final String CRLF = "\r\n";

    private final List<Map.Entry<String, String>> fields;

    private AmiPacket(List<Map.Entry<String, String>> fields) {
        this.fields = fields;
    }

    /** 用交替的 key/value 造一个 Action 报文 */
    public static AmiPacket action(String action, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value 必须成对");
        }
        List<Map.Entry<String, String>> f = new ArrayList<>();
        f.add(Map.entry("Action", action));
        for (int i = 0; i < keyValues.length; i += 2) {
            String v = keyValues[i + 1];
            if (v != null && !v.isBlank()) {
                f.add(Map.entry(keyValues[i], v));
            }
        }
        return new AmiPacket(f);
    }

    /** 追加一个字段(用于 Variable 这类可重复的 key), 返回新报文 */
    public AmiPacket with(String key, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        List<Map.Entry<String, String>> f = new ArrayList<>(fields);
        f.add(Map.entry(key, value));
        return new AmiPacket(f);
    }

    /**
     * 解析一个报文体(不含结尾空行)。
     *
     * <p>无冒号的行直接丢弃 —— AMI 里偶尔混入非 key/value 的行(如某些版本的欢迎语残留),
     * 为一行畸形数据丢掉整个报文不划算。
     */
    public static AmiPacket parse(String raw) {
        List<Map.Entry<String, String>> f = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            f.add(Map.entry(line.substring(0, colon).trim(), line.substring(colon + 1).trim()));
        }
        return new AmiPacket(f);
    }

    /** 编码成线路格式(含结尾空行) */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append(CRLF);
        }
        return sb.append(CRLF).toString();
    }

    /** 取第一个匹配值(key 大小写不敏感 —— AMI 各版本大小写并不统一); 无则 null */
    public String get(String key) {
        for (Map.Entry<String, String> e : fields) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    public String getOrDefault(String key, String fallback) {
        String v = get(key);
        return v == null ? fallback : v;
    }

    /** 取全部同名值(如多条 Variable) */
    public List<String> all(String key) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : fields) {
            if (e.getKey().equalsIgnoreCase(key)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** 事件名; 不是事件则 null */
    public String event() {
        return get("Event");
    }

    /** 关联 id: 我们用它把 Originate 的响应/结果对回发起方 */
    public String actionId() {
        return get("ActionID");
    }

    /** AMI 的 {@code Response:} 是否为 Success */
    public boolean isSuccess() {
        return "Success".equalsIgnoreCase(getOrDefault("Response", ""));
    }

    public boolean isResponse() {
        return get("Response") != null;
    }

    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields) {
            m.putIfAbsent(e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public String toString() {
        return fields.toString();
    }
}
