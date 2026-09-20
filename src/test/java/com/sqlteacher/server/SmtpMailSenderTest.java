package com.sqlteacher.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class SmtpMailSenderTest {
    private static final MailSender NO_OP = (to, subject, body) -> { };

    @Test void missingOrBlankHostFallsBackToTheFileOutbox() {
        assertSame(NO_OP, SmtpMailSender.fromConfig(Map.of(), NO_OP));
        assertSame(NO_OP, SmtpMailSender.fromConfig(Map.of(SmtpMailSender.SMTP_HOST, "   "), NO_OP),
            "a blank host must behave exactly like an unconfigured channel");
    }

    @Test void hostWithoutADerivableFromAddressFallsBackRatherThanLosingMail() {
        assertSame(NO_OP, SmtpMailSender.fromConfig(Map.of(SmtpMailSender.SMTP_HOST, "smtp.example.com"), NO_OP),
            "SMTP_FROM or SMTP_USERNAME must provide a from address");
        assertInstanceOf(SmtpMailSender.class, SmtpMailSender.fromConfig(Map.of(
            SmtpMailSender.SMTP_HOST, "smtp.example.com",
            SmtpMailSender.SMTP_USERNAME, "noreply@example.com"), NO_OP));
    }

    @Test void deliveryFailureNeverPropagatesToTheCaller() {
        MailSender channel = SmtpMailSender.fromConfig(Map.of(
            SmtpMailSender.SMTP_HOST, "127.0.0.1",
            SmtpMailSender.SMTP_PORT, "1", // connection refused; async delivery must swallow and log
            SmtpMailSender.SMTP_USERNAME, "noreply@example.com"), NO_OP);
        assertDoesNotThrow(() -> channel.send("student@example.com", "SQLTeacher 密码重置", "您的密码重置验证码：123456"));
    }

    @Test void implicitSslChannelIsConfigurable() {
        // v3.8.0：隐式 SSL（465）配置可解析并创建通道（阿里云邮件推送等仅提供 SSL 端口的服务商）。
        MailSender channel = SmtpMailSender.fromConfig(Map.of(
            SmtpMailSender.SMTP_HOST, "smtpdm.aliyun.com",
            SmtpMailSender.SMTP_PORT, "465",
            SmtpMailSender.SMTP_SSL, "true",
            SmtpMailSender.SMTP_USERNAME, "noreply@example.com"), NO_OP);
        assertInstanceOf(SmtpMailSender.class, channel);
        assertDoesNotThrow(() -> channel.send("student@example.com", "SQLTeacher 邮箱验证", "您的邮箱验证码：123456"));
    }
}
