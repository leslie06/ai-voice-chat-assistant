package com.vca.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 对话存档行(对应表 {@code conversation_turn})。MyBatis-Plus 实体: 字段驼峰 ↔ 列下划线
 * 由 {@code mapUnderscoreToCamelCase} 自动映射(见 {@code MyBatisSupport})。
 *
 * <p>由 {@link com.vca.orchestrator.recorder.TurnRecord} 转换而来; 主键 {@code id} 用 MySQL 自增。
 */
@TableName("conversation_turn")
public class ConversationTurn {

    /** 自增主键(MySQL AUTO_INCREMENT)。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 id(同一条 WebSocket 长连一个)。 */
    private String sessionId;

    /** 本会话内回合序号(从 1 递增)。 */
    private Integer turnIndex;

    /** 回合所走链路: pipeline / s2s / s2s-persistent。 */
    private String mode;

    /** 本轮用户说了什么。 */
    private String userText;

    /** 机器人本轮回复文本(动作型回合如点歌可为空)。 */
    private String assistantText;

    /** 整轮耗时(毫秒), 可空(S2S 路径无逐轮计时)。 */
    private Long totalMs;

    /** 回合结局: complete / interrupted / error。 */
    private String outcome;

    /** 落库时刻。 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(Integer turnIndex) {
        this.turnIndex = turnIndex;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getUserText() {
        return userText;
    }

    public void setUserText(String userText) {
        this.userText = userText;
    }

    public String getAssistantText() {
        return assistantText;
    }

    public void setAssistantText(String assistantText) {
        this.assistantText = assistantText;
    }

    public Long getTotalMs() {
        return totalMs;
    }

    public void setTotalMs(Long totalMs) {
        this.totalMs = totalMs;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
