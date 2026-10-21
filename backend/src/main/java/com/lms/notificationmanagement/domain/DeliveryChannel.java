package com.lms.notificationmanagement.domain;

/**
 * The four broadcast channels (Wave 11). Mirrors {@code
 * ck_notification_delivery_channel} (V67). {@code IN_APP} is written directly;
 * {@code EMAIL} goes through {@code MessagingProviderApi}; {@code SMS} and
 * {@code WHATSAPP} go through {@code MessagingChannelApi}.
 */
public enum DeliveryChannel {

	IN_APP, EMAIL, SMS, WHATSAPP

}
