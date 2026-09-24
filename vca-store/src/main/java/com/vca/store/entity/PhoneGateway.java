package com.vca.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 语音网关分机, 对应表 {@code phone_gateway}。字段含义见 {@code com.vca.orchestrator.merchant.GatewayAccount}。 */
@TableName("phone_gateway")
public class PhoneGateway {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private String accessNumber;
    private String label;
    private String lineUser;
    private String linePassword;
    private String phoneUser;
    private String phonePassword;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getAccessNumber() { return accessNumber; }
    public void setAccessNumber(String accessNumber) { this.accessNumber = accessNumber; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getLineUser() { return lineUser; }
    public void setLineUser(String lineUser) { this.lineUser = lineUser; }
    public String getLinePassword() { return linePassword; }
    public void setLinePassword(String linePassword) { this.linePassword = linePassword; }
    public String getPhoneUser() { return phoneUser; }
    public void setPhoneUser(String phoneUser) { this.phoneUser = phoneUser; }
    public String getPhonePassword() { return phonePassword; }
    public void setPhonePassword(String phonePassword) { this.phonePassword = phonePassword; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
