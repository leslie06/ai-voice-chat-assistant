package com.vca.store;

import com.vca.store.embed.Embedder;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用确定性 embedder: 按固定词表做词袋向量 —— 含相同关键词的文本向量相近(余弦高),
 * 让"语义召回/检索"在不联网的前提下可断言。
 */
class StubEmbedder implements Embedder {

    static final String[] VOCAB = {"周杰伦", "音乐", "猫", "宠物", "北京", "城市", "python", "编程"};

    @Override
    public float[] embed(String text) {
        if (text == null) {
            return null;
        }
        float[] v = new float[VOCAB.length];
        for (int i = 0; i < VOCAB.length; i++) {
            int count = 0, from = 0, idx;
            while ((idx = text.indexOf(VOCAB[i], from)) >= 0) {
                count++;
                from = idx + VOCAB[i].length();
            }
            v[i] = count;
        }
        return v;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }
}
