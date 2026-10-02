package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingDeliveryStatusEvent;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.NotificationDelivery;
import com.lms.notificationmanagement.domain.NotificationDelivery.ProviderStatus;
import com.lms.notificationmanagement.repository.NotificationDeliveryRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies verified provider status callbacks (ADR-024 V8) to the matching
 * delivery. Runs synchronously on the webhook thread, within the tenant the
 * callback URL resolved to: the event's {@code tenantId} must equal the
 * current tenant context, and the lookup is tenant-scoped, so another
 * tenant's delivery can never be touched. Only the provider-status columns
 * change; the dispatch lifecycle {@code status} never does.
 */
@Component
public class DeliveryStatusListener {

	private static final Logger log = LoggerFactory.getLogger(DeliveryStatusListener.class);

	private final NotificationDeliveryRepository deliveryRepository;

	private final TenantContext tenantContext;

	public DeliveryStatusListener(NotificationDeliveryRepository deliveryRepository, TenantContext tenantContext) {
		this.deliveryRepository = deliveryRepository;
		this.tenantContext = tenantContext;
	}

	@EventListener
	@Transactional
	public void on(MessagingDeliveryStatusEvent event) {
		if (event.tenantId() == null || !TenantContextHolder.isSet()
				|| !event.tenantId().equals(tenantContext.getTenantId())) {
			log.atWarn().setMessage("notification.delivery_status_tenant_mismatch").log();
			return;
		}
		DeliveryChannel channel = (event.channel() == Channel.SMS) ? DeliveryChannel.SMS : DeliveryChannel.WHATSAPP;
		ProviderStatus next = ProviderStatus.valueOf(event.status().name());
		Instant now = Instant.now();
		int updated = 0;
		for (NotificationDelivery delivery : deliveryRepository.findByProviderMessage(channel,
				event.providerMessageId())) {
			if (delivery.applyProviderStatus(next, event.occurredAt(), event.providerErrorCode(), now)) {
				deliveryRepository.save(delivery);
				updated++;
			}
		}
		log.atInfo()
			.setMessage("notification.delivery_status_applied")
			.addKeyValue("tenantId", event.tenantId())
			.addKeyValue("status", next)
			.addKeyValue("updated", updated)
			.log();
	}

}
