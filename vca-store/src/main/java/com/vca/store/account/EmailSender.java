package com.vca.store.account;

/**
 * 发送邮件的端口。默认实现 {@link LogEmailSender} 只把邮件打到日志(配合 dev-echo 即可在无真实邮件通道时联调);
 * 配好 SMTP 后自动用 {@link SmtpEmailSender} 真发。也可由宿主提供别的 {@code EmailSender} Bean 覆盖。
 */
public interface EmailSender {

    /** 发一封纯文本邮件。实现应自行处理异常, 不要抛到调用方。 */
    void send(String to, String subject, String body);
}
