package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import com.lms.integrationmanagement.domain.TenantMessagingWebhook;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-filtered access to {@code tenant_messaging_webhook} (ADR-024 V8).
 *
 * <p>{@link #findByWebhookKeyAcrossTenants(String)} is the one deliberate
 * cross-tenant read (ADR-006 {@code AcrossTenants} convention, as {@code
 * PaymentRepository#findByGatewayReferenceAcrossTenants}): a provider
 * callback has no session or subdomain, so the stored row's own {@code
 * tenantId} becomes the trusted tenant for the rest of that call - never
 * anything from the callback payload. Rows are never deleted.
 */
public interface TenantMessagingWebhookRepository extends TenantAwareRepository<TenantMessagingWebhook, UUID> {

	@Query("SELECT w FROM TenantMessagingWebhook w WHERE w.webhookKey = :webhookKey")
	Optional<TenantMessagingWebhook> findByWebhookKeyAcrossTenants(@Param("webhookKey") String webhookKey);

	default Optional<TenantMessagingWebhook> findForProvider(Provider provider) {
		return findOne((root, query, cb) -> cb.equal(root.get("provider"), provider));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void delete(TenantMessagingWebhook entity) {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends TenantMessagingWebhook> entities) {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<TenantMessagingWebhook> entities) {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("messaging webhooks are never deleted");
	}

}
