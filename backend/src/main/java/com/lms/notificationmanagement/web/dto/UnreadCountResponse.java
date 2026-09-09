package com.lms.notificationmanagement.web.dto;

/**
 * Backs the Notification Center nav badge (plan §9.5/§10 follow-up - the
 * frontend badge has no data source without this). Mirrors {@link
 * NotificationResponse}'s plain-record shape exactly.
 */
public record UnreadCountResponse(long unreadCount) {

}
