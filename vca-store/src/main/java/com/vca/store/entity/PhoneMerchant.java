package com.vca.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 商家资料行, 对应表 {@code phone_merchant}。字段含义见 {@code com.vca.orchestrator.merchant.MerchantProfile}。 */
@TableName("phone_merchant")
public class PhoneMerchant {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerId;
    private String number;
    private String name;
    private Boolean enabled;
    private String industry;
    private String greeting;
    private String systemPrompt;
    private String transferDialString;
    private String summaryWebhook;
    private String ttsVoice;
    private String asrVocabularyId;
    private String address;
    private String businessHours;
    private String phone;
    private String transport;
    private String services;
    private String staff;
    private String bookingRules;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }
    public String getGreeting() { return greeting; }
    public void setGreeting(String greeting) { this.greeting = greeting; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getTransferDialString() { return transferDialString; }
    public void setTransferDialString(String transferDialString) { this.transferDialString = transferDialString; }
    public String getSummaryWebhook() { return summaryWebhook; }
    public void setSummaryWebhook(String summaryWebhook) { this.summaryWebhook = summaryWebhook; }
    public String getTtsVoice() { return ttsVoice; }
    public void setTtsVoice(String ttsVoice) { this.ttsVoice = ttsVoice; }
    public String getAsrVocabularyId() { return asrVocabularyId; }
    public void setAsrVocabularyId(String asrVocabularyId) { this.asrVocabularyId = asrVocabularyId; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getBusinessHours() { return businessHours; }
    public void setBusinessHours(String businessHours) { this.businessHours = businessHours; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getServices() { return services; }
    public void setServices(String services) { this.services = services; }
    public String getStaff() { return staff; }
    public void setStaff(String staff) { this.staff = staff; }
    public String getBookingRules() { return bookingRules; }
    public void setBookingRules(String bookingRules) { this.bookingRules = bookingRules; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
