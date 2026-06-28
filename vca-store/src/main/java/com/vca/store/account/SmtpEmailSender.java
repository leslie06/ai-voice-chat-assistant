package com.vca.store.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 用 SMTP 真发邮件(基于 Spring 的 {@link JavaMailSenderImpl} + Jakarta Mail)。自带连接配置, 不依赖
 * Boot 的 MailSender 自动装配。仅当配置了 {@code vca.store.mail.host} 时由 {@code StoreAutoConfiguration} 启用。
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSenderImpl mailSender;
    private final String from;

    public SmtpEmailSender(String host, int port, String username, String password,
                           String from, boolean ssl, boolean starttls, String proxyHost, int proxyPort) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setPort(port);
        s.setUsername(username);
        s.setPassword(password);
        s.setDefaultEncoding("UTF-8");
        Properties p = s.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        p.put("mail.smtp.ssl.trust", host);   // 信任该 SMTP 主机证书, 规避证书链/SNI 问题
        if (ssl) {
            p.put("mail.smtp.ssl.enable", "true");   // 隐式 SSL(465)
        }
        if (starttls) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");   // 显式 STARTTLS(587), Java 下更稳
        }
        // 经 HTTP 代理 CONNECT 隧道发信(某些机器/网络下 JVM 直连 SMTP 被重置, 走代理可绕过)
        if (proxyHost != null && !proxyHost.isBlank()) {
            p.put("mail.smtp.proxy.host", proxyHost);
            p.put("mail.smtp.proxy.port", String.valueOf(proxyPort));
        }
        this.mailSender = s;
        this.from = (from == null || from.isBlank()) ? username : from;
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("邮件已发送: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.warn("邮件发送失败: to={}", to, e);   // 打完整异常栈以定位根因
        }
    }
}
