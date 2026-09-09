package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.notificationmanagement.domain.InAppNotification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per ADR-006 - never referenced outside {@code
 * com.lms.notificationmanagement}. Both {@code default} methods below add a
 * second, manual scoping dimension ({@code recipient_user_id}) on top of the
 * structural {@code tenant_id} filter inherited from {@link
 * TenantAwareRepository}, per plan §9.5/§14: two different users in the
 * *same* tenant must not see each other's notifications, and
 * {@code TenantAwareRepositoryImpl} only ever enforces {@code tenant_id} -
 * never any other column - so this bolt-on predicate is this repository's own
 * responsibility, not something the shared base class gives for free. This is
 * exactly what the mandatory same-tenant BOLA negative test (plan §18)
 * verifies.
 */
public interface InAppNotificationRepository extends TenantAwareRepository<InAppNotification, UUID> {

	/** The calling recipient's own Notification Center list - owner-scoped by construction. */
	default Page<InAppNotification> findByRecipientUserId(UUID recipientUserId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("recipientUserId"), recipientUserId), pageable);
	}

	/**
	 * The mark-read ownership check: {@code id} AND {@code recipientUserId}
	 * both ANDed as predicates, never {@code id} alone - a row belonging to
	 * another user (same tenant or not) simply does not match, so the calling
	 * service can turn "not found" into a uniform 404 without ever revealing
	 * whether the id exists for someone else (plan §10).
	 */
	default Optional<InAppNotification> findByIdAndRecipientUserId(UUID id, UUID recipientUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), id),
				cb.equal(root.get("recipientUserId"), recipientUserId)));
	}

	/**
	 * Backs the Notification Center nav badge count. Same double-scoping
	 * discipline as {@link #findByRecipientUserId} above (the structural
	 * {@code tenant_id} filter from {@link TenantAwareRepository}, ANDed here
	 * with an explicit {@code recipientUserId} predicate the base class does
	 * not know about) plus {@code readAt IS NULL} - never a caller-supplied
	 * {@code tenant_id}/recipient id, always the caller's own trusted id.
	 */
	default long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("recipientUserId"), recipientUserId),
				cb.isNull(root.get("readAt"))));
	}

}
