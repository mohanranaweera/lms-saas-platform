package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006/{@code .claude/rules/tenancy.md} for its
 * inherited finders ({@code save}/{@code findById}/...). {@code
 * notification_outbox} additionally needs two deliberate, explicitly-named
 * cross-tenant bypass queries for the dispatch poller (plan §9.2/§14,
 * {@code .claude/rules/backend.md}'s "any repository method that bypasses
 * the structural filter must be explicitly named... e.g.
 * findAllAcrossTenants..." rule) - dispatch-polling is inherently a
 * platform-level background operation (multiple horizontally scaled app
 * instances all draining the same queue), not a tenant-scoped one, so it
 * cannot go through the inherited {@code Specification}-backed finders (which
 * would silently scope to whatever tenant happens to be ambient on the
 * calling thread - never correct for a platform-level batch claim).
 */
public interface NotificationOutboxRepository extends TenantAwareRepository<NotificationOutbox, UUID> {

	/**
	 * Phase 1 of the poller's two-phase claim (plan §9.2 step 1): a cheap,
	 * lock-free read of candidate {@code PENDING} row ids across all tenants,
	 * oldest first. Deliberately does not lock anything itself - locking
	 * happens per-row in {@link #claimPendingByIdAcrossTenantsForUpdateSkipLocked}
	 * so one slow/contended row never blocks this batch-listing read.
	 */
	@Query(value = "SELECT id FROM notification_outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT :limit",
			nativeQuery = true)
	List<UUID> findPendingIdsAcrossTenants(@Param("limit") int limit);

	/**
	 * Phase 2 of the poller's two-phase claim (plan §9.2 step 1): a single-row,
	 * lock-acquiring, status-guarded claim. Native SQL is required here (not
	 * JPQL {@code @Lock(PESSIMISTIC_WRITE)}) because this Hibernate/JPA version
	 * has no JPQL-level way to express {@code SKIP LOCKED} - only a literal
	 * {@code FOR UPDATE SKIP LOCKED} clause in native SQL does. No {@code
	 * tenant_id} filter is applied, deliberately (dispatch is a platform-level
	 * operation, per plan §9.2/§14) - safety instead comes from the {@code
	 * WHERE status = 'PENDING'} guard: if another instance already claimed
	 * (and is processing or has finished) this row inside its own still-open
	 * transaction, {@code SKIP LOCKED} makes this query return empty rather
	 * than block or reprocess it, and if another instance already committed a
	 * terminal status for this row, the {@code status = 'PENDING'} predicate
	 * excludes it outright.
	 */
	@Query(value = "SELECT * FROM notification_outbox WHERE id = :id AND status = 'PENDING' FOR UPDATE SKIP LOCKED",
			nativeQuery = true)
	Optional<NotificationOutbox> claimPendingByIdAcrossTenantsForUpdateSkipLocked(@Param("id") UUID id);

	/**
	 * Phase 1 of the reconciliation step's own two-phase claim (V29, mirroring
	 * {@link #findPendingIdsAcrossTenants}'s exact shape/rationale one row
	 * status over): a cheap, lock-free read of candidate {@code SENDING} row
	 * ids whose {@code claimed_at} is older than {@code cutoff} - i.e. a row
	 * claimed for dispatch long enough ago that a crash between the claim and
	 * the terminal-status commit is the most plausible explanation, across
	 * all tenants, oldest-claimed first. Deliberately does not lock anything
	 * itself - locking happens per-row in {@link
	 * #claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked} so one
	 * slow/contended row never blocks this batch-listing read.
	 */
	@Query(value = "SELECT id FROM notification_outbox WHERE status = 'SENDING' AND claimed_at < :cutoff "
			+ "ORDER BY claimed_at LIMIT :limit", nativeQuery = true)
	List<UUID> findStuckSendingIdsAcrossTenants(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

	/**
	 * Phase 2 of the reconciliation step's own two-phase claim (V29): a
	 * single-row, lock-acquiring, status-AND-claim-age-guarded claim -
	 * mirrors {@link #claimPendingByIdAcrossTenantsForUpdateSkipLocked}'s
	 * exact rationale for using native SQL / {@code FOR UPDATE SKIP LOCKED} /
	 * no {@code tenant_id} filter. The {@code claimed_at < :cutoff}
	 * re-check (not just {@code status = 'SENDING'}) matters here
	 * specifically: without it, a row claimed by a real, still-in-flight
	 * dispatch attempt AFTER this method's caller last listed candidate ids
	 * (phase 1 above) could otherwise be re-matched and incorrectly marked
	 * {@code FAILED} out from under that in-flight attempt - re-applying the
	 * same cutoff at claim time, not just at listing time, keeps a
	 * recently-claimed row safe from reconciliation for its entire timeout
	 * window, however much time elapses between the two phases.
	 */
	@Query(value = "SELECT * FROM notification_outbox WHERE id = :id AND status = 'SENDING' "
			+ "AND claimed_at < :cutoff FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<NotificationOutbox> claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked(@Param("id") UUID id,
			@Param("cutoff") Instant cutoff);

}
