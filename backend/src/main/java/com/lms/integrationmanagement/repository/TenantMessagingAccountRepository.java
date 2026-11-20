package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.domain.TenantMessagingAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-filtered access to {@code tenant_messaging_account} (Wave 11). No
 * method takes a tenant id: every read is scoped to the resolved tenant
 * context. Rows are never deleted - deliveries reference the account that
 * sent them - so every delete method is disabled.
 */
public interface TenantMessagingAccountRepository extends TenantAwareRepository<TenantMessagingAccount, UUID> {

	default Optional<TenantMessagingAccount> findActive(Channel channel) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("channel"), channel),
				cb.equal(root.get("status"), TenantMessagingAccount.Status.ACTIVE)));
	}

	/** The newest row for this channel, active or not - the tenant's current state for the settings page. */
	default Optional<TenantMessagingAccount> findLatest(Channel channel) {
		return findAll((root, query, cb) -> cb.equal(root.get("channel"), channel),
				Sort.by(Sort.Direction.DESC, "createdAt"))
			.stream()
			.findFirst();
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void delete(TenantMessagingAccount entity) {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAll(Iterable<? extends TenantMessagingAccount> entities) {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllInBatch(Iterable<TenantMessagingAccount> entities) {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("messaging accounts are never deleted - disable instead");
	}

}
