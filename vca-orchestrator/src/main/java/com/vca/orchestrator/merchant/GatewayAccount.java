package com.vca.orchestrator.merchant;

import java.time.LocalDateTime;

/**
 * 一台语音网关(HT813 这类 ATA)在 FreeSWITCH 上的两个分机。一家店一台网关。
 *
 * <p>LINE 口(FXO, 接电话线)的来电一律按 {@code accessNumber} 认领门店, 不看网关"转 VoIP"框里手填的号码;
 * PHONE 口(FXS, 接话机)是这家店的转人工分机, 也是 AI 接不了时来电转去的座机。
 *
 * @param id            主键; 新建时为 null
 * @param merchantId    所属门店(phone_merchant.id)
 * @param accessNumber  门店接入号(开通时的快照, 门店改号后以门店为准重新生成)
 * @param label         门店名, 写进分机文件注释, 值守脚本告警时用
 * @param lineUser      LINE 口分机号, 如 8011
 * @param linePassword  LINE 口 SIP 密码
 * @param phoneUser     PHONE 口分机号, 如 8012
 * @param phonePassword PHONE 口 SIP 密码
 * @param createdAt     开通时间
 */
public record GatewayAccount(Long id, long merchantId, String accessNumber, String label,
                             String lineUser, String linePassword, String phoneUser, String phonePassword,
                             LocalDateTime createdAt) {

    /** 转人工分机在 FreeSWITCH 里的拨号串 */
    public String phoneDialString() {
        return "user/" + phoneUser + "@" + MerchantRules.FS_DOMAIN;
    }
}
