package com.mainbank.notification.service;

/** Delivery channel. The shipped implementation only logs; an e-mail, SMS or push sender plugs in here. */
public interface NotificationSender {

    void send(Notice notice);
}
