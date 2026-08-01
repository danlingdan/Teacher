package com.sqlteacher.server;

/** Outbound mail boundary for the cloud API. Never contains plaintext passwords. */
public interface MailSender {
    void send(String to, String subject, String body);
}
