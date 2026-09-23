package com.vca.domain.spi;

import java.util.List;

/**
 * 识别热词表的厂商侧管理: 建表、查表、改词。
 *
 * <p>热词是窄带电话线路上提准的主要手段(实测「洗牙」不带热词被识别成「抵押」, 带上就对了),
 * 但表要先在厂商那边注册并<b>与识别模型绑定</b>, 换模型得重建。与 {@link VoiceCloner} 一样,
 * 这是账号级的低频操作, 与每句都要走的实时识别链路分开。
 *
 * <p>实现只做厂商调用, 不做任何策略: 哪些词进表、几张表、什么时候改, 都是调用方的事。
 */
public interface VocabularyClient {

    /** 查表的结果: 厂商 id、绑定的识别模型、表里的词(只要文本, 权重由实现统一定) */
    record Snapshot(String id, String targetModel, List<String> words) {
    }

    /**
     * 列出某个前缀下已有的表 id, 新的在前。前缀是建表时给的, 厂商会把它编进 id 里,
     * 调用方靠它在重启后找回自己建过的表, 不必另存映射。
     */
    List<String> list(String prefix);

    /** 查一张表的内容; 表不存在或查询失败抛异常 */
    Snapshot query(String vocabularyId);

    /**
     * @param prefix      表名前缀(实现会规整成厂商要求的形式)
     * @param targetModel 绑定的识别模型, 必须与识别时用的模型完全一致
     * @param words       热词文本
     * @return 厂商表 id
     */
    String create(String prefix, String targetModel, List<String> words);

    /** 整表覆盖成新的词 */
    void update(String vocabularyId, List<String> words);

    /** 删表; 失败不抛异常, 返回云端是否确实删掉了 */
    boolean delete(String vocabularyId);
}
