package com.lms.notificationmanagement.service;

import java.time.Instant;
import java.util.UUID;

public record InAppNotificationView(UUID id, String title, String body, Instant readAt, Instant createdAt) {

}
