package com.lms.tenantmanagement.repository;

import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately a plain {@link JpaRepository}, never a
 * {@code TenantAwareRepository}. {@code Tenant} does not implement
 * {@code TenantOwned} (see that entity's doc comment), so
 * {@code TenantAwareRepositoryFactoryBean} already falls back to standard
 * Spring Data JPA behavior for this repository automatically - there is
 * nothing tenant-scoped to enforce here, since {@code tenant} rows are the
 * platform's root identity records, not owned by any tenant.
 *
 * <p>{@code TEN-2}'s approve/reject flow (narrowed scope - see
 * {@code docs/plans/MVP-020 Platform Admin Dashboard.md} §21 item 1, "Option
 * A") is implemented here via {@link #findByStatus(TenantStatus, Pageable)}
 * (the approval-queue listing, plain derived query - no "AcrossTenants"
 * naming needed, since there is no structural tenant filter to bypass) and
 * {@link #findByIdForUpdate(UUID)} (the pessimistic-lock read {@code
 * TenantApprovalService} uses to close the concurrent-approval race).
 * Suspend/cancel of an already-{@code ACTIVE}/{@code TRIAL} tenant remains
 * explicitly deferred (plan §6) - no method for that is added here.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

	Optional<Tenant> findBySubdomain(String subdomain);

	boolean existsBySubdomain(String subdomain);

	Page<Tenant> findByStatus(TenantStatus status, Pageable pageable);

	/**
	 * Pessimistic-write-locked single-row read, used by {@code
	 * TenantApprovalService#approve}/{@code #reject} to close the concurrent-
	 * approval race: mirrors {@code
	 * com.lms.paymentmanagement.payment.repository.PaymentRepository
	 * #findByGatewayReferenceAcrossTenantsForUpdate}'s exact rationale - a
	 * plain check-then-update at the service layer alone cannot prevent two
	 * concurrent Platform Admins both reading {@code PENDING_APPROVAL} before
	 * either writes. The second caller blocks on this row lock until the
	 * first transaction commits, then re-reads the now-updated status and
	 * gets {@code TenantApprovalService}'s "already processed" {@code 409},
	 * never a duplicate mutation or duplicate audit row.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM Tenant t WHERE t.id = :id")
	Optional<Tenant> findByIdForUpdate(@Param("id") UUID id);

}
