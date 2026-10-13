package com.mainbank.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notice notice) {
        log.info("NOTIFY {} <{}> [{}]: {}", notice.recipient(), notice.iban(), notice.role(), notice.text());
    }
}
