package com.lms.paymentmanagement.discount.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped (ADR-006) access to {@code discount_rule} (V92). Finders are
 * {@code default} methods over the inherited, structurally tenant-filtered
 * {@code findAll}/{@code findOne}. Rows are never deleted (archive = {@code
 * archived_at}, D11); only the header's mutable columns change through {@code
 * save}.
 */
public interface DiscountRuleRepository extends AppendOnlyTenantAwareRepository<DiscountRule, UUID> {

	/** Every non-archived rule of the tenant, newest first (admin list). */
	default List<DiscountRule> findAllLive() {
		return findAll((root, query, cb) -> cb.isNull(root.get("archivedAt")),
				Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
	}

	/** Checkout candidates: non-archived and active. */
	default List<DiscountRule> findAllLiveActive() {
		return findAll((root, query, cb) -> cb.and(cb.isNull(root.get("archivedAt")), cb.isTrue(root.get("active"))),
				Sort.by("id"));
	}

	/** The non-archived rule holding {@code codeNormalized} (the live-code unique index makes it at most one). */
	default Optional<DiscountRule> findLiveByCode(String codeNormalized) {
		return findOne((root, query, cb) -> cb.and(cb.isNull(root.get("archivedAt")),
				cb.equal(root.get("codeNormalized"), codeNormalized)));
	}

	/**
	 * {@code SELECT ... FOR UPDATE} on one rule of the CURRENT tenant (D12): the
	 * capacity check + reservation of capped rules is serialized on this row
	 * lock. The tenant is read from the trusted context here, never passed by
	 * a caller.
	 */
	default Optional<DiscountRule> lockById(UUID id) {
		return lockByIdUnchecked(id, new TenantContextHolder().getTenantId());
	}

	/** Use {@link #lockById(UUID)}; the tenant id must come from the trusted context. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM DiscountRule r WHERE r.id = :id AND r.tenantId = :tenantId")
	Optional<DiscountRule> lockByIdUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	/**
	 * D12 order placement: {@code SELECT ... FOR UPDATE} (in id order, so two
	 * placements can never deadlock on rules) of every live, active rule of the
	 * CURRENT tenant that could apply to a cart over {@code courseIds} (a
	 * required course is in the cart; a code rule only when its code is {@code
	 * codeNormalized}) that is a code rule or capped (Maximum Uses set) - the
	 * rules the capacity and one-use-per-student checks apply to (owner
	 * decision 2026-10-21); uncapped automatic rules need no serialization.
	 * Returns ids only, so no entity is loaded before the lock is held.
	 */
	default List<UUID> lockRuleIdsForCart(Collection<UUID> courseIds, String codeNormalized) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return lockRuleIdsForCartUnchecked(new TenantContextHolder().getTenantId(), courseIds,
				codeNormalized == null ? "" : codeNormalized);
	}

	/** Use {@link #lockRuleIdsForCart}; the tenant id must come from the trusted context. */
	@Query(nativeQuery = true, value = """
			SELECT r.id FROM discount_rule r
			JOIN discount_rule_version v ON v.tenant_id = r.tenant_id AND v.id = r.current_version_id
			WHERE r.tenant_id = :tenantId AND r.archived_at IS NULL AND r.active = true
			  AND (r.discount_type <> 'DISCOUNT_CODE' OR r.code_normalized = :code)
			  AND (r.discount_type = 'DISCOUNT_CODE' OR v.max_uses IS NOT NULL)
			  AND EXISTS (SELECT 1 FROM discount_rule_version_course c
			              WHERE c.tenant_id = v.tenant_id AND c.version_id = v.id
			                AND c.role = 'REQUIRED' AND c.course_id IN (:courseIds))
			ORDER BY r.id
			FOR UPDATE OF r""")
	List<UUID> lockRuleIdsForCartUnchecked(@Param("tenantId") UUID tenantId,
			@Param("courseIds") Collection<UUID> courseIds, @Param("code") String code);

}
