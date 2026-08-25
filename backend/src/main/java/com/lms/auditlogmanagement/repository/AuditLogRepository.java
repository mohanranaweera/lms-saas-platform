package com.lms.auditlogmanagement.repository;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.common.persistence.TenantAwareRepository;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@code audit_log} is fully append-only, so
 * (mirroring {@code PaymentRefundRepository}/{@code EnrollmentRepository}'s
 * exact pattern) every delete-shaped method inherited from {@link
 * TenantAwareRepository}/{@code JpaRepository} is overridden below to fail
 * loudly - no repository method anywhere may delete an audit row, including
 * for a platform admin.
 */
public interface AuditLogRepository extends TenantAwareRepository<AuditLog, UUID> {

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(AuditLog entity) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends AuditLog> entities) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<AuditLog> entities) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

}
