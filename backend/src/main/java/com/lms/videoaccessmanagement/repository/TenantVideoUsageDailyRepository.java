package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.domain.TenantVideoUsageDaily;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped per ADR-006. Counters change only through single atomic
 * native statements (the {@code VideoWatchProgressRepository} upsert
 * technique), so two concurrent uploads can neither lose an increment nor
 * both take the last slot of the daily budget. The {@code ...InTenant}
 * methods are called only by the default methods above them, which take the
 * tenant from the tenant context - never from a caller.
 */
public interface TenantVideoUsageDailyRepository extends TenantAwareRepository<TenantVideoUsageDaily, UUID> {

	default Optional<TenantVideoUsageDaily> findForDay(LocalDate usageDate) {
		return findOne((root, query, cb) -> cb.equal(root.get("usageDate"), usageDate));
	}

	/** Takes one upload slot of {@code usageDate} if fewer than {@code limit} are used. */
	default boolean tryReserveUpload(LocalDate usageDate, int limit, Instant now) {
		UUID tenantId = new TenantContextHolder().getTenantId();
		ensureRowInTenant(UuidV7Generator.generate(), tenantId, usageDate, now);
		return incrementUploadsBelowLimitInTenant(tenantId, usageDate, limit, now) == 1;
	}

	/** Gives one upload slot back (never below zero). */
	default void releaseUpload(LocalDate usageDate, Instant now) {
		decrementUploadsInTenant(new TenantContextHolder().getTenantId(), usageDate, now);
	}

	/** Adds transferred bytes / provider API units to today's row. */
	default void addUsage(LocalDate usageDate, long bytes, long apiUnits, Instant now) {
		UUID tenantId = new TenantContextHolder().getTenantId();
		ensureRowInTenant(UuidV7Generator.generate(), tenantId, usageDate, now);
		addUsageInTenant(tenantId, usageDate, bytes, apiUnits, now);
	}

	@Transactional
	@Modifying
	@Query(value = """
			INSERT INTO tenant_video_usage_daily (id, tenant_id, usage_date, uploads, bytes, api_units, created_at, updated_at)
			VALUES (:id, :tenantId, :usageDate, 0, 0, 0, :now, :now)
			ON CONFLICT (tenant_id, usage_date) DO NOTHING
			""", nativeQuery = true)
	void ensureRowInTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
			@Param("usageDate") LocalDate usageDate, @Param("now") Instant now);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE tenant_video_usage_daily SET uploads = uploads + 1, updated_at = :now
			WHERE tenant_id = :tenantId AND usage_date = :usageDate AND uploads < :limit
			""", nativeQuery = true)
	int incrementUploadsBelowLimitInTenant(@Param("tenantId") UUID tenantId, @Param("usageDate") LocalDate usageDate,
			@Param("limit") int limit, @Param("now") Instant now);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE tenant_video_usage_daily SET uploads = uploads - 1, updated_at = :now
			WHERE tenant_id = :tenantId AND usage_date = :usageDate AND uploads > 0
			""", nativeQuery = true)
	int decrementUploadsInTenant(@Param("tenantId") UUID tenantId, @Param("usageDate") LocalDate usageDate,
			@Param("now") Instant now);

	@Transactional
	@Modifying
	@Query(value = """
			UPDATE tenant_video_usage_daily
			SET bytes = bytes + :bytes, api_units = api_units + :apiUnits, updated_at = :now
			WHERE tenant_id = :tenantId AND usage_date = :usageDate
			""", nativeQuery = true)
	int addUsageInTenant(@Param("tenantId") UUID tenantId, @Param("usageDate") LocalDate usageDate,
			@Param("bytes") long bytes, @Param("apiUnits") long apiUnits, @Param("now") Instant now);

}
