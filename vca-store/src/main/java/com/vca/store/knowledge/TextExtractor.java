package com.vca.store.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/** 把上传文件的字节按类型抽成纯文本: pdf 走 PDFBox, 其余(txt/md/markdown…)按 UTF-8 文本读。 */
public final class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    private TextExtractor() {
    }

    public static String extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return extractPdf(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String extractPdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        } catch (Exception e) {
            log.warn("PDF 解析失败: {}", e.toString());
            return "";
        }
    }
}
