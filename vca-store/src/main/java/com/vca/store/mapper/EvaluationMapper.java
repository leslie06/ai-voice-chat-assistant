package com.vca.store.mapper;

import com.vca.store.eval.ModeAggRow;
import com.vca.store.eval.OutcomeRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评测查询(数据飞轮 P2-A)。SQL 刻意写成 <b>H2 / MySQL 通用</b>(用 CASE WHEN 求和、CHAR_LENGTH 算字符数,
 * 不依赖 MySQL 专有的布尔求和或 PERCENTILE 函数 —— 分位数交给 Java 算), 这样单测能在 H2 上可靠跑。
 *
 * <p>所有查询都带 {@code created_at >= #{since}} 时间窗(全量时传一个很早的时刻), 免动态 SQL。
 */
public interface EvaluationMapper {

    @Select("SELECT COUNT(*) FROM conversation_turn WHERE created_at >= #{since}")
    long totalTurns(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(DISTINCT session_id) FROM conversation_turn WHERE created_at >= #{since}")
    long totalSessions(@Param("since") LocalDateTime since);

    @Select("""
            SELECT mode,
                   COUNT(*) AS total,
                   SUM(CASE WHEN outcome = 'error' THEN 1 ELSE 0 END) AS errors,
                   SUM(CASE WHEN outcome = 'interrupted' THEN 1 ELSE 0 END) AS interrupts
            FROM conversation_turn
            WHERE created_at >= #{since}
            GROUP BY mode
            ORDER BY total DESC""")
    List<ModeAggRow> aggByMode(@Param("since") LocalDateTime since);

    @Select("""
            SELECT outcome, COUNT(*) AS cnt
            FROM conversation_turn
            WHERE created_at >= #{since}
            GROUP BY outcome
            ORDER BY cnt DESC""")
    List<OutcomeRow> aggByOutcome(@Param("since") LocalDateTime since);

    /** 有逐轮计时的回合(三段式)的 total_ms 列表; 分位数在 Java 计算。 */
    @Select("SELECT total_ms FROM conversation_turn WHERE created_at >= #{since} AND total_ms IS NOT NULL")
    List<Long> latencies(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM conversation_turn WHERE created_at >= #{since} AND outcome = 'complete'")
    long completeTurns(@Param("since") LocalDateTime since);

    /** complete 但助手文本为空: 疑似内容审查命中 / 模型沉默。 */
    @Select("""
            SELECT COUNT(*) FROM conversation_turn
            WHERE created_at >= #{since} AND outcome = 'complete'
              AND (assistant_text IS NULL OR assistant_text = '')""")
    long emptyCompleteTurns(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM conversation_turn WHERE created_at >= #{since} AND outcome = 'interrupted'")
    long interruptedTurns(@Param("since") LocalDateTime since);

    /** interrupted 且助手刚开口就被打断(文本极短): 疑似回声自打断。 */
    @Select("""
            SELECT COUNT(*) FROM conversation_turn
            WHERE created_at >= #{since} AND outcome = 'interrupted'
              AND (assistant_text IS NULL OR CHAR_LENGTH(assistant_text) < #{minChars})""")
    long shortInterruptedTurns(@Param("since") LocalDateTime since, @Param("minChars") int minChars);

    /** 多步 Agent 回合数(agent_steps 非空即 Agent 回合)。 */
    @Select("SELECT COUNT(*) FROM conversation_turn WHERE created_at >= #{since} AND agent_steps IS NOT NULL")
    long agentTurns(@Param("since") LocalDateTime since);

    /** Agent 回合的总执行步数(均值在 Java 算, 避免方言差异)。 */
    @Select("SELECT COALESCE(SUM(agent_steps), 0) FROM conversation_turn "
            + "WHERE created_at >= #{since} AND agent_steps IS NOT NULL")
    long agentStepsSum(@Param("since") LocalDateTime since);

    /** Agent 回合的总反思补步数。 */
    @Select("SELECT COALESCE(SUM(agent_replans), 0) FROM conversation_turn "
            + "WHERE created_at >= #{since} AND agent_replans IS NOT NULL")
    long agentReplansSum(@Param("since") LocalDateTime since);
}
