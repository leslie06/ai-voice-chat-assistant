package com.vca.store;

import com.vca.store.knowledge.KnowledgeService;
import com.vca.store.knowledge.MyBatisKnowledgeStore;
import com.vca.store.mapper.KnowledgeChunkMapper;
import com.vca.store.mapper.KnowledgeDocMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RAG 入库 + 检索: 上传切块入库, 据问题语义召回相关片段, 按 userId 隔离, 无 embedder 时返回空。 */
class KnowledgeSearchTest {

    private static DataSource h2(String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE knowledge_doc (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      title VARCHAR(255) NOT NULL,
                      created_at TIMESTAMP NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE knowledge_chunk (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      doc_id BIGINT NOT NULL,
                      ordinal INT NOT NULL,
                      content TEXT NOT NULL,
                      embedding BLOB,
                      created_at TIMESTAMP NOT NULL
                    )""");
        }
        return ds;
    }

    @Test
    void ingestThenSearch() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("kb"));
        KnowledgeDocMapper docs = MyBatisSupport.mapper(factory, KnowledgeDocMapper.class);
        KnowledgeChunkMapper chunks = MyBatisSupport.mapper(factory, KnowledgeChunkMapper.class);
        StubEmbedder embedder = new StubEmbedder();
        KnowledgeService service = new KnowledgeService(docs, chunks, embedder);
        MyBatisKnowledgeStore store = new MyBatisKnowledgeStore(chunks, embedder);

        // 两段不同主题, 句号分隔保证切成多块
        int n = service.ingest(1L, "笔记",
                "我最喜欢的歌手是周杰伦, 他的音乐很棒。我家养了一只猫作为宠物。");
        assertThat(n).isGreaterThanOrEqualTo(1);

        List<String> hit = store.search("1", "推荐音乐");
        assertThat(hit).anyMatch(s -> s.contains("周杰伦"));

        assertThat(store.search("2", "音乐")).isEmpty();   // 别的用户检索不到
    }

    @Test
    void withoutEmbedderSearchReturnsEmpty() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("kb_noembed"));
        KnowledgeDocMapper docs = MyBatisSupport.mapper(factory, KnowledgeDocMapper.class);
        KnowledgeChunkMapper chunks = MyBatisSupport.mapper(factory, KnowledgeChunkMapper.class);
        KnowledgeService service = new KnowledgeService(docs, chunks, null);   // 无 embedder
        MyBatisKnowledgeStore store = new MyBatisKnowledgeStore(chunks, null);

        service.ingest(1L, "笔记", "一些内容。");
        assertThat(store.search("1", "内容")).isEmpty();
    }

    @Test
    void deleteRemovesChunks() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("kb_del"));
        KnowledgeDocMapper docs = MyBatisSupport.mapper(factory, KnowledgeDocMapper.class);
        KnowledgeChunkMapper chunks = MyBatisSupport.mapper(factory, KnowledgeChunkMapper.class);
        StubEmbedder embedder = new StubEmbedder();
        KnowledgeService service = new KnowledgeService(docs, chunks, embedder);
        MyBatisKnowledgeStore store = new MyBatisKnowledgeStore(chunks, embedder);

        service.ingest(1L, "笔记", "我喜欢周杰伦的音乐。");
        Long docId = service.list(1L).get(0).getId();
        assertThat(service.delete(1L, docId)).isTrue();
        assertThat(store.search("1", "音乐")).isEmpty();
        assertThat(service.delete(2L, docId)).isFalse();   // 越权删除失败
    }
}
