package com.vca.orchestrator.merchant;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 门店资料里<b>直通电话线路</b>的几项: 谁能改、能填成什么样。纯函数, 接口层调用。
 *
 * <p>分两类字段:
 * <ul>
 *   <li><b>运营字段</b>(接入号、转人工拨号串、热词表、音色): 只有管理员能改。接入号决定哪家店的来电归谁;
 *       拨号串会原样交给 FreeSWITCH 的 {@code bridge} 执行; 音色可能是别人复刻的真人声音。商家更新资料时这几项
 *       一律沿用库里的原值, 请求里传了也不理。</li>
 *   <li><b>推送地址</b>: 商家自己填(群机器人是他们的), 但只收企业微信/钉钉机器人 —— 否则服务器会替人往任意地址发请求,
 *       包括本机的管理端口。</li>
 * </ul>
 * 校验对管理员同样生效: 这些值最终进 FreeSWITCH 命令, 管理员手滑填错的后果和恶意填的一样。
 */
public final class MerchantRules {

    /** FreeSWITCH 目录的域, 见 deploy/freeswitch/conf/freeswitch.xml 的 domain 变量 */
    public static final String FS_DOMAIN = "vca.local";

    private static final Pattern NUMBER = Pattern.compile("^[0-9]{3,20}$");
    /** 只填分机号: 最常见的情况(网关 PHONE 口那个分机), 服务端补成拨号串 */
    private static final Pattern EXTENSION = Pattern.compile("^[0-9]{2,10}$");
    /** 注册分机: user/8002@vca.local */
    private static final Pattern USER_DIAL = Pattern.compile("^user/[0-9A-Za-z_.-]{1,32}@[0-9A-Za-z_.-]{1,64}$");
    /** 走中继打手机: sofia/gateway/trunk/13800138000 */
    private static final Pattern GATEWAY_DIAL = Pattern.compile("^sofia/gateway/[0-9A-Za-z_-]{1,32}/\\+?[0-9]{3,20}$");
    private static final Set<String> WEBHOOK_HOSTS = Set.of("qyapi.weixin.qq.com", "oapi.dingtalk.com");

    private MerchantRules() {
    }

    /** 接入号: 纯数字 3~20 位(网关转过来的被叫号码就是这种) */
    public static String number(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (!NUMBER.matcher(s).matches()) {
            throw new IllegalArgumentException("接入号只能是 3~20 位数字");
        }
        return s;
    }

    /**
     * 转人工拨号串。只放行两种形状, 其余一律拒绝 —— 带 {@code {变量}}、逗号、别的 endpoint 的串
     * 能让 FreeSWITCH 呼到任意 SIP 地址或在新通道上执行命令。
     *
     * @return 规整后的拨号串; 空输入返回空串(= 不下发转人工工具)
     */
    public static String dialString(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return "";
        }
        if (EXTENSION.matcher(s).matches()) {
            return "user/" + s + "@" + FS_DOMAIN;
        }
        if (USER_DIAL.matcher(s).matches() || GATEWAY_DIAL.matcher(s).matches()) {
            return s;
        }
        throw new IllegalArgumentException("转人工只能填分机号(如 8002), 或 user/分机@域、sofia/gateway/网关/号码");
    }

    /** 小结推送地址: 只收企业微信/钉钉群机器人的 https 地址; 空 = 只落库不推送 */
    public static String webhook(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return "";
        }
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("推送地址格式不对");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !WEBHOOK_HOSTS.contains(host)
                || uri.getPort() != -1 || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("推送地址只支持企业微信或钉钉群机器人的 https 地址");
        }
        return s;
    }

    /** 管理员提交的资料: 运营字段照收, 但要过校验 */
    public static MerchantProfile checkedByAdmin(MerchantProfile p) {
        return with(p, number(p.number()), dialString(p.transferDialString()), webhook(p.summaryWebhook()),
                p.ttsVoice(), p.asrVocabularyId());
    }

    /** 商家自己提交的资料: 运营字段沿用库里的原值, 推送地址要过校验 */
    public static MerchantProfile checkedByOwner(MerchantProfile incoming, MerchantProfile existing) {
        return with(incoming, existing.number(), existing.transferDialString(), webhook(incoming.summaryWebhook()),
                existing.ttsVoice(), existing.asrVocabularyId());
    }

    private static MerchantProfile with(MerchantProfile p, String number, String dialString, String webhook,
                                        String ttsVoice, String vocabularyId) {
        return new MerchantProfile(p.id(), p.ownerId(), number, p.name(), p.enabled(), p.industry(),
                p.greeting(), p.systemPrompt(), dialString, webhook, ttsVoice, vocabularyId,
                p.address(), p.businessHours(), p.phone(), p.transport(), p.services(), p.staff(),
                p.bookingRules(), p.notes(), p.createdAt(), p.updatedAt());
    }
}
