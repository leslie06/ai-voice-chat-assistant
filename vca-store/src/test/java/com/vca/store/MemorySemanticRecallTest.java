package com.vca.store;

import com.vca.store.mapper.UserMemoryMapper;
import com.vca.store.memory.MyBatisMemoryStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 长期记忆向量召回: 按当前问题语义召回相关条目; 无 query / 无 embedder 时退回最近 N。 */
class MemorySemanticRecallTest {

    private static DataSource h2(String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE user_memory (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      content VARCHAR(512) NOT NULL,
                      embedding BLOB,
                      created_at TIMESTAMP NOT NULL
                    )""");
        }
        return ds;
    }

    @Test
    void recallByQueryReturnsRelevant() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("mem_sem"));
        UserMemoryMapper mapper = MyBatisSupport.mapper(factory, UserMemoryMapper.class);
        MyBatisMemoryStore store = new MyBatisMemoryStore(mapper, new StubEmbedder());

        store.remember("1", "用户喜欢周杰伦的音乐");
        store.remember("1", "用户养了一只猫当宠物");
        store.remember("1", "用户在北京这座城市工作");

        // 问音乐 → 命中第一条, 不应把猫/北京当成最相关
        List<String> hit = store.recall("1", "给我推荐点好听的音乐");
        assertThat(hit).isNotEmpty();
        assertThat(hit.get(0)).contains("周杰伦");
        assertThat(hit).noneMatch(s -> s.contains("猫"));
    }

    @Test
    void isolatedByUser() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("mem_iso"));
        UserMemoryMapper mapper = MyBatisSupport.mapper(factory, UserMemoryMapper.class);
        MyBatisMemoryStore store = new MyBatisMemoryStore(mapper, new StubEmbedder());
        store.remember("1", "用户喜欢周杰伦的音乐");

        assertThat(store.recall("2", "音乐")).isEmpty();   // 别的用户检索不到
    }

    @Test
    void nullQueryFallsBackToRecent() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("mem_recent"));
        UserMemoryMapper mapper = MyBatisSupport.mapper(factory, UserMemoryMapper.class);
        MyBatisMemoryStore store = new MyBatisMemoryStore(mapper, new StubEmbedder());
        store.remember("1", "用户喜欢周杰伦的音乐");
        store.remember("1", "用户养了一只猫");

        List<String> all = store.recall("1", null);   // 无 query → 最近 N(两条都在)
        assertThat(all).hasSize(2);
    }

    @Test
    void withoutEmbedderDegradesToRecent() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("mem_noembed"));
        UserMemoryMapper mapper = MyBatisSupport.mapper(factory, UserMemoryMapper.class);
        MyBatisMemoryStore store = new MyBatisMemoryStore(mapper, null);   // 无 embedder
        store.remember("1", "用户喜欢周杰伦的音乐");

        // 即便带 query, 无 embedder 也走最近 N, 仍能拿到记忆(不退化为空)
        assertThat(store.recall("1", "音乐")).hasSize(1);
    }
}
