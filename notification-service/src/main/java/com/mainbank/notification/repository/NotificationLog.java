package com.mainbank.notification.repository;

import com.mainbank.notification.service.Notice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Record of the notifications that were sent; it is what makes a redelivered event harmless. */
@Repository
public class NotificationLog {

    private final JdbcClient jdbc;

    public NotificationLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadySent(UUID transferId, String role) {
        return jdbc.sql("select count(*) from notifications where transfer_id = :id and role = :role")
                .param("id", transferId).param("role", role)
                .query(Integer.class).single() > 0;
    }

    public void markSent(UUID transferId, Notice notice) {
        jdbc.sql("""
                        insert into notifications (transfer_id, role, recipient, iban, message)
                        values (:id, :role, :recipient, :iban, :message)
                        on conflict (transfer_id, role) do nothing""")
                .param("id", transferId).param("role", notice.role())
                .param("recipient", notice.recipient()).param("iban", notice.iban())
                .param("message", notice.text())
                .update();
    }
}
