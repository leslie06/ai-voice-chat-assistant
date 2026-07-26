package com.vca.store.entity;

import java.time.LocalDateTime;

/** 用户上传歌曲记录，对应 {@code user_music_upload}。 */
public class UserMusicUpload {

    private String id;
    private Long userId;
    private String title;
    private String artist;
    private String originalFilename;
    private String audioObjectKey;
    private String lyricsObjectKey;
    private Long audioBytes;
    private Long lyricsBytes;
    private Boolean rightsConfirmed;
    private String status;
    private String textLabels;
    private String textReason;
    private String audioTaskId;
    private String audioRiskLevel;
    private String moderationLabels;
    private String moderationReason;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getAudioObjectKey() { return audioObjectKey; }
    public void setAudioObjectKey(String audioObjectKey) { this.audioObjectKey = audioObjectKey; }
    public String getLyricsObjectKey() { return lyricsObjectKey; }
    public void setLyricsObjectKey(String lyricsObjectKey) { this.lyricsObjectKey = lyricsObjectKey; }
    public Long getAudioBytes() { return audioBytes; }
    public void setAudioBytes(Long audioBytes) { this.audioBytes = audioBytes; }
    public Long getLyricsBytes() { return lyricsBytes; }
    public void setLyricsBytes(Long lyricsBytes) { this.lyricsBytes = lyricsBytes; }
    public Boolean getRightsConfirmed() { return rightsConfirmed; }
    public void setRightsConfirmed(Boolean rightsConfirmed) { this.rightsConfirmed = rightsConfirmed; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTextLabels() { return textLabels; }
    public void setTextLabels(String textLabels) { this.textLabels = textLabels; }
    public String getTextReason() { return textReason; }
    public void setTextReason(String textReason) { this.textReason = textReason; }
    public String getAudioTaskId() { return audioTaskId; }
    public void setAudioTaskId(String audioTaskId) { this.audioTaskId = audioTaskId; }
    public String getAudioRiskLevel() { return audioRiskLevel; }
    public void setAudioRiskLevel(String audioRiskLevel) { this.audioRiskLevel = audioRiskLevel; }
    public String getModerationLabels() { return moderationLabels; }
    public void setModerationLabels(String moderationLabels) { this.moderationLabels = moderationLabels; }
    public String getModerationReason() { return moderationReason; }
    public void setModerationReason(String moderationReason) { this.moderationReason = moderationReason; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
