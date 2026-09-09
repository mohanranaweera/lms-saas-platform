package com.lms.notificationmanagement.service;

/** Output of {@link NotificationTemplateRenderer#render}. */
public record RenderedNotification(String subject, String body) {

}
