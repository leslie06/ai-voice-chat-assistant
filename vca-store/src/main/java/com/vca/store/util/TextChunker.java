package com.vca.store.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 把长文本切成大致等长、带少量重叠的片段, 供 RAG 切块入库。纯函数, 可单测。
 * 优先在句界(。!?；\n 及英文 .!?)断开, 找不到再硬切; 片段间留 {@code overlap} 字符重叠以保上下文连续。
 */
public final class TextChunker {

    private static final int DEFAULT_SIZE = 500;
    private static final int DEFAULT_OVERLAP = 50;

    private TextChunker() {
    }

    public static List<String> chunk(String text) {
        return chunk(text, DEFAULT_SIZE, DEFAULT_OVERLAP);
    }

    public static List<String> chunk(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        // 归一空白: 连续空白/换行压成单空格, 去首尾
        String s = text.replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) {
            return out;
        }
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (overlap < 0 || overlap >= size) {
            overlap = Math.min(DEFAULT_OVERLAP, size / 4);
        }
        int n = s.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(start + size, n);
            if (end < n) {
                int boundary = lastBoundary(s, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String piece = s.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= n) {
                break;
            }
            start = Math.max(end - overlap, start + 1);   // 留重叠, 但保证前进
        }
        return out;
    }

    /** 在 [from, to) 内找最后一个句界(其后位置), 找不到返回 -1。 */
    private static int lastBoundary(String s, int from, int to) {
        for (int i = to - 1; i > from; i--) {
            char c = s.charAt(i);
            if (c == '。' || c == '!' || c == '?' || c == '；' || c == '\n'
                    || c == '.' || c == '!' || c == '?' || c == ';') {
                return i + 1;
            }
        }
        return -1;
    }
}
