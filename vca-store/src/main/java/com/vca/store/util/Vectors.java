package com.vca.store.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 向量小工具: float[] ↔ byte[](BLOB 存库)打包、余弦相似度、暴力 top-K。
 * 纯函数, 无状态, 可单测。数据量(单用户几十~几千条)下暴力余弦毫秒级即够; 过万再换专用向量库。
 */
public final class Vectors {

    private Vectors() {
    }

    /** float[] → byte[](小端 float32 紧凑打包), 存进 BLOB。 */
    public static byte[] toBytes(float[] v) {
        if (v == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    /** byte[] → float[]; 长度非 4 的倍数或为空返回 null。 */
    public static float[] fromBytes(byte[] b) {
        if (b == null || b.length == 0 || b.length % 4 != 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }

    /** 余弦相似度; 维度不一致或零向量返回 0。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 一个候选项: 携带原始负载 {@code item} 与其向量。 */
    public record Scored<T>(T item, double score) {
    }

    /**
     * 暴力 top-K: 对每个候选算与 {@code query} 的余弦, 过滤掉低于 {@code minScore} 的, 取分最高的 K 个(降序)。
     *
     * @param vectorOf 从候选取其向量(可返回 null, 表示该候选无向量, 跳过)
     */
    public static <T> List<Scored<T>> topK(float[] query, List<T> candidates,
                                           java.util.function.Function<T, float[]> vectorOf,
                                           int k, double minScore) {
        List<Scored<T>> scored = new ArrayList<>();
        if (query == null || candidates == null) {
            return scored;
        }
        for (T c : candidates) {
            float[] v = vectorOf.apply(c);
            if (v == null) {
                continue;
            }
            double s = cosine(query, v);
            if (s >= minScore) {
                scored.add(new Scored<>(c, s));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored<T>::score).reversed());
        return scored.size() > k ? scored.subList(0, k) : scored;
    }
}
