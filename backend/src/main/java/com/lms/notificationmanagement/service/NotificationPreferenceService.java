package com.lms.notificationmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.NotificationChannelPreference;
import com.lms.notificationmanagement.repository.NotificationChannelPreferenceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A recipient's own broadcast channel consent (Wave 11, PAR-22-03). Always
 * self-scoped: the user id is the authenticated caller's, never a parameter.
 * In-app notifications have no preference; transactional messages (payment
 * outcomes, password links) are not affected by these settings.
 */
@Service
public class NotificationPreferenceService {

	private static final List<DeliveryChannel> CONFIGURABLE = List.of(DeliveryChannel.EMAIL, DeliveryChannel.SMS,
			DeliveryChannel.WHATSAPP);

	private final NotificationChannelPreferenceRepository repository;

	private final TenantContext tenantContext;

	public NotificationPreferenceService(NotificationChannelPreferenceRepository repository,
			TenantContext tenantContext) {
		this.repository = repository;
		this.tenantContext = tenantContext;
	}

	public record PreferenceView(String channel, boolean optedIn, boolean explicitOptInRequired) {

	}

	@Transactional(readOnly = true)
	public List<PreferenceView> myPreferences() {
		UUID me = CommunicationActors.currentUserId();
		List<PreferenceView> views = new ArrayList<>();
		for (DeliveryChannel channel : CONFIGURABLE) {
			boolean optedIn = repository.findForUser(me, channel)
				.map(NotificationChannelPreference::isOptedIn)
				.orElse(NotificationChannelPreference.defaultFor(channel));
			views.add(new PreferenceView(channel.name(), optedIn, !NotificationChannelPreference.defaultFor(channel)));
		}
		return views;
	}

	@Transactional
	public List<PreferenceView> updateMyPreferences(Map<String, Boolean> changes) {
		if (changes == null || changes.isEmpty()) {
			throw new FieldValidationException("Invalid preferences",
					List.of(new FieldError("preferences", "must include at least one channel")));
		}
		UUID me = CommunicationActors.currentUserId();
		Instant now = Instant.now();
		for (Map.Entry<String, Boolean> change : changes.entrySet()) {
			DeliveryChannel channel = CONFIGURABLE.stream()
				.filter(candidate -> candidate.name().equals(change.getKey()))
				.findFirst()
				.orElseThrow(() -> new FieldValidationException("Invalid preferences",
						List.of(new FieldError("preferences", "unknown channel " + change.getKey()))));
			if (change.getValue() == null) {
				throw new FieldValidationException("Invalid preferences",
						List.of(new FieldError("preferences." + channel.name(), "must be true or false")));
			}
			NotificationChannelPreference preference = repository.findForUser(me, channel)
				.orElseGet(() -> new NotificationChannelPreference(tenantContext.getTenantId(), me, channel,
						change.getValue(), now));
			preference.setOptedIn(change.getValue(), now);
			repository.save(preference);
		}
		return myPreferences();
	}

}
