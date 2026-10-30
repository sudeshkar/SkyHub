package com.skyhub.notification.persistence;

import com.skyhub.notification.domain.SentNotification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentNotificationRepository extends JpaRepository<SentNotification, Long> {

    boolean existsByEventId(UUID eventId);
}
