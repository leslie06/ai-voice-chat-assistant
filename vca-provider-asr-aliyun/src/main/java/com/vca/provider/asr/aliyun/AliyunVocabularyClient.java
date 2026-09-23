package com.vca.provider.asr.aliyun;

import com.alibaba.dashscope.audio.asr.vocabulary.Vocabulary;
import com.alibaba.dashscope.audio.asr.vocabulary.VocabularyService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vca.domain.enums.Capability;
import com.vca.domain.enums.VendorType;
import com.vca.domain.exception.ProviderException;
import com.vca.domain.spi.VocabularyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 阿里云百炼的定制热词(speech-biasing): 建表 / 列表 / 查表 / 改词 / 删表。
 *
 * <p>表 id 实测形如 {@code vocab-vca-0123456789abcdef0123456789abcdef}, 即 "vocab-前缀-32 位十六进制",
 * 所以按前缀列表就能找回自己建过的表。前缀规则与声音复刻一样: 仅小写字母数字、少于 10 个字符。
 *
 * <p>权重统一给 {@value #WEIGHT}(取值 1~5): 实测 4 已经足以把「洗牙」从「抵押」里拉回来,
 * 再高会开始把不相干的音也往热词上靠。
 */
public class AliyunVocabularyClient implements VocabularyClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunVocabularyClient.class);

    static final int WEIGHT = 4;
    private static final int MAX_PREFIX = 9;
    private static final int PAGE_SIZE = 50;

    private final AliyunAsrProperties props;

    public AliyunVocabularyClient(AliyunAsrProperties props) {
        this.props = props;
    }

    @Override
    public List<String> list(String prefix) {
        try {
            Vocabulary[] page = service().listVocabulary(sanitize(prefix), 0, PAGE_SIZE);
            List<String> ids = new ArrayList<>();
            if (page != null) {
                for (Vocabulary v : page) {
                    if (v != null && v.getVocabularyId() != null) {
                        ids.add(v.getVocabularyId());
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            throw wrap("列热词表失败", e);
        }
    }

    @Override
    public Snapshot query(String vocabularyId) {
        try {
            Vocabulary v = service().queryVocabulary(vocabularyId);
            if (v == null) {
                throw ProviderException.fatal(VendorType.ALIYUN, Capability.ASR, "热词表不存在: " + vocabularyId, null);
            }
            return new Snapshot(vocabularyId, v.getTargetModel(), texts(v.getVocabulary()));
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw wrap("查热词表失败: " + vocabularyId, e);
        }
    }

    @Override
    public String create(String prefix, String targetModel, List<String> words) {
        try {
            Vocabulary v = service().createVocabulary(targetModel, sanitize(prefix), toArray(words));
            String id = v == null ? null : v.getVocabularyId();
            if (id == null || id.isBlank()) {
                throw ProviderException.fatal(VendorType.ALIYUN, Capability.ASR, "建热词表未返回 id", null);
            }
            log.info("热词表已建: id={}, model={}, 词数={}", id, targetModel, words.size());
            return id;
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw wrap("建热词表失败", e);
        }
    }

    @Override
    public void update(String vocabularyId, List<String> words) {
        try {
            service().updateVocabulary(vocabularyId, toArray(words));
            log.info("热词表已更新: id={}, 词数={}", vocabularyId, words.size());
        } catch (Exception e) {
            throw wrap("改热词表失败: " + vocabularyId, e);
        }
    }

    @Override
    public boolean delete(String vocabularyId) {
        try {
            service().deleteVocabulary(vocabularyId);
            return true;
        } catch (Exception e) {
            log.warn("删热词表失败: id={}, err={}", vocabularyId, e.toString());
            return false;
        }
    }

    private VocabularyService service() {
        return new VocabularyService(props.getApiKey());
    }

    private static ProviderException wrap(String what, Exception e) {
        return ProviderException.fatal(VendorType.ALIYUN, Capability.ASR, what + ": " + e.getMessage(), e);
    }

    static JsonArray toArray(List<String> words) {
        JsonArray arr = new JsonArray();
        for (String w : words) {
            JsonObject o = new JsonObject();
            o.addProperty("text", w);
            o.addProperty("weight", WEIGHT);
            o.addProperty("lang", "zh");
            arr.add(o);
        }
        return arr;
    }

    static List<String> texts(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (JsonElement e : arr) {
            if (e != null && e.isJsonObject() && e.getAsJsonObject().has("text")) {
                out.add(e.getAsJsonObject().get("text").getAsString());
            }
        }
        return out;
    }

    /** 前缀只保留小写字母数字并截断; 全被过滤掉时兜一个固定值 */
    static String sanitize(String prefix) {
        if (prefix == null) {
            return "vca";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : prefix.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            }
            if (sb.length() == MAX_PREFIX) {
                break;
            }
        }
        return sb.isEmpty() ? "vca" : sb.toString();
    }
}
