package com.lms.integrationmanagement.repository;

import com.lms.integrationmanagement.domain.ClassSessionProviderEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately a plain {@link JpaRepository}, NOT {@code TenantAwareRepository}
 * - {@link ClassSessionProviderEvent} is a platform-level webhook-idempotency
 * ledger, not a tenant-owned business entity (see that class's own javadoc).
 * No caller of this repository should ever read across it for
 * tenant-scoped business logic; it exists solely to enforce webhook delivery
 * idempotency via the schema-level {@code uq_class_session_provider_event}
 * constraint.
 */
public interface ClassSessionProviderEventRepository extends JpaRepository<ClassSessionProviderEvent, UUID> {

}
