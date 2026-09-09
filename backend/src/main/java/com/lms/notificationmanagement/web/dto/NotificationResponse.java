package com.lms.notificationmanagement.web.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, String title, String body, Instant readAt, Instant createdAt) {

}
