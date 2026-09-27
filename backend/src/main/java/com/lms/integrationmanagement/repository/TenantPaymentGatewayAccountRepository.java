package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.GatewayAccountStatus;
import com.lms.integrationmanagement.domain.GatewayProvider;
import com.lms.integrationmanagement.domain.TenantPaymentGatewayAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-filtered (via {@link TenantAwareRepository}) access to
 * {@code tenant_payment_gateway_account}. No method takes a tenant id: every
 * read is scoped to the resolved tenant context. Rows are never deleted -
 * payments reference the account that collected them.
 */
public interface TenantPaymentGatewayAccountRepository
		extends TenantAwareRepository<TenantPaymentGatewayAccount, UUID> {

	default Optional<TenantPaymentGatewayAccount> findActive(GatewayProvider provider) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("provider"), provider),
				cb.equal(root.get("status"), GatewayAccountStatus.ACTIVE)));
	}

	/** The newest row for this provider, active or not - the tenant's "current" account state. */
	default Optional<TenantPaymentGatewayAccount> findLatest(GatewayProvider provider) {
		return findAll((root, query, cb) -> cb.equal(root.get("provider"), provider),
				Sort.by(Sort.Direction.DESC, "createdAt"))
			.stream()
			.findFirst();
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void delete(TenantPaymentGatewayAccount entity) {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAll(Iterable<? extends TenantPaymentGatewayAccount> entities) {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllInBatch(Iterable<TenantPaymentGatewayAccount> entities) {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("gateway accounts are never deleted - disable instead");
	}

}
