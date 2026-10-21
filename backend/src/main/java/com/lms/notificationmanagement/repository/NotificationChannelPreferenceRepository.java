package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.NotificationChannelPreference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-filtered access to {@code notification_channel_preference} (Wave 11).
 * Every lookup is additionally keyed by the user id the caller passes - for
 * the self-service endpoints that is always the authenticated user's own id.
 */
public interface NotificationChannelPreferenceRepository
		extends TenantAwareRepository<NotificationChannelPreference, UUID> {

	default List<NotificationChannelPreference> findForUser(UUID userId) {
		return findAll((root, query, cb) -> cb.equal(root.get("userId"), userId));
	}

	default Optional<NotificationChannelPreference> findForUser(UUID userId, DeliveryChannel channel) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("channel"), channel)));
	}

}
