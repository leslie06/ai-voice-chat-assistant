package com.vca.store;

import com.vca.orchestrator.recorder.TurnRecord;
import com.vca.store.entity.ConversationTurn;
import com.vca.store.mapper.ConversationTurnMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 落库器: 异步入队 → 后台逐条 insert → close 时排空。用 H2(MySQL 兼容模式)替身验证
 * 实体↔列映射与 {@code BaseMapper.insert} 的注入, 免起真实 MySQL。
 */
class MyBatisConversationRecorderTest {

    /** 建 H2(MySQL 模式)库 + 表(与 schema.sql 同结构, 但用 H2 可解析的最小 DDL)。 */
    private static DataSource h2(String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE conversation_turn (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      session_id VARCHAR(128) NOT NULL,
                      turn_index INT NOT NULL,
                      mode VARCHAR(32) NOT NULL,
                      user_text TEXT,
                      assistant_text TEXT,
                      total_ms BIGINT,
                      outcome VARCHAR(16),
                      created_at TIMESTAMP NOT NULL
                    )""");
        }
        return ds;
    }

    private static ConversationTurnMapper mapper(DataSource ds) {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(ds);
        return MyBatisSupport.mapper(factory);
    }

    @Test
    void recordsAreAsyncWrittenAndFlushedOnClose() throws Exception {
        ConversationTurnMapper m = mapper(h2("rec"));
        MyBatisConversationRecorder recorder = new MyBatisConversationRecorder(m, 100);

        recorder.recordTurn(new TurnRecord("s-1", 1, "pipeline", "你好", "你也好", Instant.now(), 320L, "complete"));
        recorder.recordTurn(new TurnRecord("s-1", 2, "s2s", "几点了", "下午三点", Instant.now(), null, "complete"));
        recorder.close();   // 优雅排空: 关闭后队列里的都应已落库

        List<ConversationTurn> rows = m.selectList(null);
        assertThat(rows).hasSize(2);

        ConversationTurn first = rows.stream().filter(r -> r.getTurnIndex() == 1).findFirst().orElseThrow();
        assertThat(first.getSessionId()).isEqualTo("s-1");
        assertThat(first.getUserText()).isEqualTo("你好");
        assertThat(first.getAssistantText()).isEqualTo("你也好");
        assertThat(first.getMode()).isEqualTo("pipeline");
        assertThat(first.getOutcome()).isEqualTo("complete");
        assertThat(first.getTotalMs()).isEqualTo(320L);
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getId()).isNotNull();   // 自增主键回填

        // 可空 total_ms(S2S 路径)正确落成 NULL
        ConversationTurn second = rows.stream().filter(r -> r.getTurnIndex() == 2).findFirst().orElseThrow();
        assertThat(second.getTotalMs()).isNull();
    }

    @Test
    void nullRecordIsIgnored() throws Exception {
        ConversationTurnMapper m = mapper(h2("nullrec"));
        MyBatisConversationRecorder recorder = new MyBatisConversationRecorder(m, 100);
        recorder.recordTurn(null);
        recorder.close();
        assertThat(m.selectList(null)).isEmpty();
    }
}
