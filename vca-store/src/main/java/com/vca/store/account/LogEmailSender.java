package com.vca.store.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认邮件发送器: 把邮件打到服务端日志(不真发)。配合 dev-echo(把重置令牌回给前端)即可在没有 SMTP 时
 * 完整联调找回/修改密码流程。生产配好 SMTP(vca.store.mail.*)后会自动改用 {@link SmtpEmailSender}。
 */
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("【邮件·开发模式】收件人={}, 主题={}\n{}\n(未对接真实 SMTP)", to, subject, body);
    }
}
