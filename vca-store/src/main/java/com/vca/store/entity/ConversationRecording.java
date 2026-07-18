package com.vca.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 本地双轨录音元数据，对应 {@code conversation_recording}。 */
@TableName("conversation_recording")
public class ConversationRecording {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private Long conversationId;
    private String sessionId;
    private String ossBucket;
    private String userFile;
    private String assistantFile;
    private Integer userSampleRate;
    private Integer assistantSampleRate;
    private Long userBytes;
    private Long assistantBytes;
    private Long durationMs;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getOssBucket() { return ossBucket; }
    public void setOssBucket(String ossBucket) { this.ossBucket = ossBucket; }
    public String getUserFile() { return userFile; }
    public void setUserFile(String userFile) { this.userFile = userFile; }
    public String getAssistantFile() { return assistantFile; }
    public void setAssistantFile(String assistantFile) { this.assistantFile = assistantFile; }
    public Integer getUserSampleRate() { return userSampleRate; }
    public void setUserSampleRate(Integer userSampleRate) { this.userSampleRate = userSampleRate; }
    public Integer getAssistantSampleRate() { return assistantSampleRate; }
    public void setAssistantSampleRate(Integer assistantSampleRate) { this.assistantSampleRate = assistantSampleRate; }
    public Long getUserBytes() { return userBytes; }
    public void setUserBytes(Long userBytes) { this.userBytes = userBytes; }
    public Long getAssistantBytes() { return assistantBytes; }
    public void setAssistantBytes(Long assistantBytes) { this.assistantBytes = assistantBytes; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
