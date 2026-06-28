package com.vca.store;

import com.vca.store.util.Vectors;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 向量工具: 打包往返、余弦、暴力 top-K。 */
class VectorsTest {

    @Test
    void bytesRoundTrip() {
        float[] v = {1.5f, -2.25f, 0f, 3.125f};
        assertThat(Vectors.fromBytes(Vectors.toBytes(v))).containsExactly(v);
    }

    @Test
    void fromBytesRejectsBadLength() {
        assertThat(Vectors.fromBytes(new byte[]{1, 2, 3})).isNull();   // 非 4 的倍数
        assertThat(Vectors.fromBytes(null)).isNull();
        assertThat(Vectors.fromBytes(new byte[0])).isNull();
    }

    @Test
    void cosineBasics() {
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{1, 0})).isCloseTo(1.0, within(1e-6));
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{0, 1})).isCloseTo(0.0, within(1e-6));
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{-1, 0})).isCloseTo(-1.0, within(1e-6));
        assertThat(Vectors.cosine(new float[]{1, 1}, new float[]{1})).isZero();          // 维度不一致
        assertThat(Vectors.cosine(new float[]{0, 0}, new float[]{1, 1})).isZero();        // 零向量
    }

    @Test
    void topKRanksAndFilters() {
        float[] q = {1, 0};
        List<float[]> cands = Arrays.asList(   // 含 null, 不能用 List.of
                new float[]{1, 0},      // cos 1.0
                new float[]{0.9f, 0.1f},// 高
                new float[]{0, 1},      // cos 0 → 被 minScore 滤掉
                null);                  // 无向量 → 跳过
        var top = Vectors.topK(q, cands, v -> v, 2, 0.5);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).score()).isGreaterThanOrEqualTo(top.get(1).score());
        assertThat(top.get(0).item()).isEqualTo(cands.get(0));
    }
}
