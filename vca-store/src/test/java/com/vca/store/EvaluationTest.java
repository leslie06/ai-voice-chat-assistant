package com.vca.store;

import com.vca.store.entity.ConversationTurn;
import com.vca.store.eval.ConversationEvaluator;
import com.vca.store.eval.EvaluationReport;
import com.vca.store.mapper.ConversationTurnMapper;
import com.vca.store.mapper.EvaluationMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 评测查询(P2-A): 在 H2(MySQL 模式)播种多模式/多结局数据, 校验报告每项指标。 */
class EvaluationTest {

    private static DataSource h2(String name) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        try (var c = ds.getConnection(); var st = c.createStatement()) {
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

    private static ConversationTurn row(String session, int idx, String mode, String assistant,
                                        Long totalMs, String outcome) {
        ConversationTurn t = new ConversationTurn();
        t.setSessionId(session);
        t.setTurnIndex(idx);
        t.setMode(mode);
        t.setUserText("问题" + idx);
        t.setAssistantText(assistant);
        t.setTotalMs(totalMs);
        t.setOutcome(outcome);
        t.setCreatedAt(LocalDateTime.now());
        return t;
    }

    @Test
    void reportComputesAllMetrics() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("eval"));
        ConversationTurnMapper writer = MyBatisSupport.mapper(factory, ConversationTurnMapper.class);
        EvaluationMapper reader = MyBatisSupport.mapper(factory, EvaluationMapper.class);

        // pipeline: 4 个有计时的 complete(延迟 100/200/300/400) + 1 error + 1 空回复 complete
        writer.insert(row("s1", 1, "pipeline", "你好啊朋友", 100L, "complete"));
        writer.insert(row("s1", 2, "pipeline", "今天天气不错呢", 200L, "complete"));
        writer.insert(row("s1", 3, "pipeline", "好的没问题啦", 300L, "complete"));
        writer.insert(row("s2", 4, "pipeline", "嗯嗯明白了", 400L, "complete"));
        writer.insert(row("s2", 5, "pipeline", null, null, "error"));
        writer.insert(row("s2", 6, "pipeline", "", null, "complete"));          // 空回复
        // s2s-persistent: 1 complete + 1 短打断(疑似回声) + 1 长打断(真打断)
        writer.insert(row("s3", 1, "s2s-persistent", "这是一段完整的回复内容", null, "complete"));
        writer.insert(row("s3", 2, "s2s-persistent", "嗯", null, "interrupted"));   // 短 → 疑似误打断
        writer.insert(row("s3", 3, "s2s-persistent", "我正在详细解释这个问题的时候被打断了", null, "interrupted"));

        EvaluationReport r = new ConversationEvaluator(reader).report(null);

        assertThat(r.totalTurns()).isEqualTo(9);
        assertThat(r.totalSessions()).isEqualTo(3);

        // 按模式: pipeline 6 条(error 1), s2s-persistent 3 条(interrupted 2)
        EvaluationReport.ModeStat pipeline = r.byMode().stream()
                .filter(m -> m.mode().equals("pipeline")).findFirst().orElseThrow();
        assertThat(pipeline.total()).isEqualTo(6);
        assertThat(pipeline.errorRate()).isCloseTo(1.0 / 6, within(1e-4));
        assertThat(pipeline.interruptRate()).isZero();

        EvaluationReport.ModeStat s2s = r.byMode().stream()
                .filter(m -> m.mode().equals("s2s-persistent")).findFirst().orElseThrow();
        assertThat(s2s.total()).isEqualTo(3);
        assertThat(s2s.interruptRate()).isCloseTo(2.0 / 3, within(1e-4));

        // 按结局
        assertThat(r.byOutcome()).extracting(EvaluationReport.OutcomeStat::outcome)
                .containsExactlyInAnyOrder("complete", "error", "interrupted");
        long complete = r.byOutcome().stream().filter(o -> o.outcome().equals("complete"))
                .findFirst().orElseThrow().count();
        assertThat(complete).isEqualTo(6);

        // 延迟: [100,200,300,400] → p50=200, p95=400, avg=250
        assertThat(r.latency().count()).isEqualTo(4);
        assertThat(r.latency().p50Ms()).isEqualTo(200);
        assertThat(r.latency().p95Ms()).isEqualTo(400);
        assertThat(r.latency().avgMs()).isEqualTo(250);

        // 空回复率: 6 个 complete 里 1 个空 = 1/6
        assertThat(r.emptyReplyRate()).isCloseTo(1.0 / 6, within(1e-4));
        // 疑似误打断率: 2 个 interrupted 里 1 个极短 = 1/2
        assertThat(r.suspectedFalseBargeRate()).isCloseTo(0.5, within(1e-4));
    }

    @Test
    void emptyDatabaseGivesZeroedReport() throws Exception {
        SqlSessionFactory factory = MyBatisSupport.sqlSessionFactory(h2("evalempty"));
        EvaluationReport r = new ConversationEvaluator(
                MyBatisSupport.mapper(factory, EvaluationMapper.class)).report(null);

        assertThat(r.totalTurns()).isZero();
        assertThat(r.totalSessions()).isZero();
        assertThat(r.byMode()).isEmpty();
        assertThat(r.latency().count()).isZero();
        assertThat(r.latency().p50Ms()).isZero();
        assertThat(r.emptyReplyRate()).isZero();
        assertThat(r.suspectedFalseBargeRate()).isZero();
    }

    @Test
    void percentileNearestRank() {
        List<Long> values = List.of(400L, 100L, 300L, 200L);   // 乱序输入
        EvaluationReport.LatencyStat s = ConversationEvaluator.latencyOf(values);
        assertThat(s.p50Ms()).isEqualTo(200);
        assertThat(s.p95Ms()).isEqualTo(400);
        assertThat(s.avgMs()).isEqualTo(250);
        assertThat(s.count()).isEqualTo(4);
    }
}
