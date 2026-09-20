package com.sqlteacher.server;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * Real SMTP mail channel for the cloud API (v3.8.0 ACC-S1). Configuration is
 * environment-driven; when {@code SQLTEACHER_CLOUD_SMTP_HOST} is not configured
 * the caller's fallback sender (the file outbox) is returned unchanged, so local
 * and test behavior stays deterministic and the deployment can never lose mail
 * silently by misconfiguring only the credentials.
 *
 * <p>Delivery is asynchronous on a virtual thread: mail failures must never turn
 * an anti-enumeration endpoint into an error signal, and the request path must
 * not block on a remote SMTP server. Failure logs keep the failure class and the
 * recipient domain only, never the local part or the credentials.</p>
 */
public final class SmtpMailSender implements MailSender {
    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);
    static final String SMTP_HOST = "SQLTEACHER_CLOUD_SMTP_HOST";
    static final String SMTP_PORT = "SQLTEACHER_CLOUD_SMTP_PORT";
    static final String SMTP_USERNAME = "SQLTEACHER_CLOUD_SMTP_USERNAME";
    static final String SMTP_PASSWORD = "SQLTEACHER_CLOUD_SMTP_PASSWORD";
    static final String SMTP_FROM = "SQLTEACHER_CLOUD_SMTP_FROM";
    static final String SMTP_STARTTLS = "SQLTEACHER_CLOUD_SMTP_STARTTLS";
    static final String SMTP_SSL = "SQLTEACHER_CLOUD_SMTP_SSL";
    private static final int DEFAULT_PORT = 587;

    private final Session session;
    private final String fromAddress;

    private SmtpMailSender(String host, int port, String username, String password, String from,
                           boolean startTls, boolean ssl) {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", Integer.toString(port));
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");
        boolean authenticate = username != null && !username.isBlank();
        properties.put("mail.smtp.auth", Boolean.toString(authenticate));
        if (ssl) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.socketFactory.port", Integer.toString(port));
            properties.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            properties.put("mail.smtp.socketFactory.fallback", "false");
        }
        properties.put("mail.smtp.starttls.enable", Boolean.toString(startTls && !ssl));
        this.fromAddress = from;
        this.session = Session.getInstance(properties, authenticate ? new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password == null ? "" : password);
            }
        } : null);
    }

    /** Returns the environment-driven channel: SMTP when configured, otherwise the given fallback. */
    static MailSender fromEnvironment(MailSender fallback) {
        return fromConfig(System.getenv(), fallback);
    }

    static MailSender fromConfig(Map<String, String> config, MailSender fallback) {
        String host = value(config, SMTP_HOST);
        if (host.isEmpty()) return fallback;
        String username = value(config, SMTP_USERNAME);
        String from = value(config, SMTP_FROM);
        if (from.isEmpty()) from = username;
        if (from.isEmpty()) {
            log.warn("SMTP mail channel disabled: {} is set but neither {} nor {} provides a from address; "
                + "falling back to the file outbox", SMTP_HOST, SMTP_FROM, SMTP_USERNAME);
            return fallback;
        }
        int port = DEFAULT_PORT;
        try {
            if (!value(config, SMTP_PORT).isEmpty()) port = Integer.parseInt(value(config, SMTP_PORT));
        } catch (NumberFormatException badConfiguration) {
            log.warn("SMTP mail channel fell back to the default port: {} is not a number", SMTP_PORT);
        }
        boolean startTls = !"false".equalsIgnoreCase(value(config, SMTP_STARTTLS));
        // v3.8.0:隐式 SSL(如阿里云邮件推送 465 端口)——SSL 与 STARTTLS 互斥,SSL 优先。
        boolean ssl = "true".equalsIgnoreCase(value(config, SMTP_SSL));
        return new SmtpMailSender(host, port, username, config.get(SMTP_PASSWORD), from, startTls, ssl);
    }

    @Override public void send(String to, String subject, String body) {
        Thread.ofVirtual().name("smtp-mail").start(() -> deliver(to, subject, body));
    }

    private void deliver(String to, String subject, String body) {
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            message.setSentDate(new Date());
            Transport.send(message);
        } catch (Exception error) {
            log.warn("SMTP mail delivery failed for recipient domain {}: {}",
                recipientDomain(to), error.getClass().getSimpleName());
        }
    }

    private static String recipientDomain(String to) {
        int at = to == null ? -1 : to.lastIndexOf('@');
        return at < 0 ? "unknown" : to.substring(at + 1);
    }

    private static String value(Map<String, String> config, String name) {
        String raw = config.get(name);
        return raw == null ? "" : raw.strip();
    }
}
