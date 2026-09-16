package com.vca.store.entity;

import java.time.LocalDateTime;

/** {@code user_voice_clone} 行对象。 */
public class UserVoiceClone {

    private String voiceId;
    private Long userId;
    private String name;
    private String targetModel;
    private Integer sampleSeconds;
    private Boolean rightsConfirmed;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;

    public String getVoiceId() {
        return voiceId;
    }

    public void setVoiceId(String voiceId) {
        this.voiceId = voiceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetModel() {
        return targetModel;
    }

    public void setTargetModel(String targetModel) {
        this.targetModel = targetModel;
    }

    public Integer getSampleSeconds() {
        return sampleSeconds;
    }

    public void setSampleSeconds(Integer sampleSeconds) {
        this.sampleSeconds = sampleSeconds;
    }

    public Boolean getRightsConfirmed() {
        return rightsConfirmed;
    }

    public void setRightsConfirmed(Boolean rightsConfirmed) {
        this.rightsConfirmed = rightsConfirmed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
