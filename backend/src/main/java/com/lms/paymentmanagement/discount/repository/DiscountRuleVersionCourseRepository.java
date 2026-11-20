package com.lms.paymentmanagement.discount.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.paymentmanagement.discount.domain.DiscountCourseRole;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersionCourse;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped, insert-only access to {@code discount_rule_version_course}
 * (V92) - immutable with its version; deletes are refused.
 */
public interface DiscountRuleVersionCourseRepository
		extends AppendOnlyTenantAwareRepository<DiscountRuleVersionCourse, DiscountRuleVersionCourse.Key> {

	default List<DiscountRuleVersionCourse> findAllByVersionIdIn(Collection<UUID> versionIds) {
		if (versionIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("versionId").in(versionIds));
	}

	/**
	 * (reward course id, free months) of the CURRENT version of each live rule
	 * in {@code ruleIds} of the current tenant - a scalar projection (no
	 * managed entities), used to take the coverage locks of the possible reward
	 * months before an order is priced.
	 */
	default List<Object[]> findRewardWindows(Collection<UUID> ruleIds) {
		if (ruleIds.isEmpty()) {
			return List.of();
		}
		return findRewardWindowsUnchecked(new TenantContextHolder().getTenantId(), ruleIds, DiscountCourseRole.REWARD);
	}

	/** Use {@link #findRewardWindows}; the tenant id must come from the trusted context. */
	@Query("""
			SELECT c.courseId, v.freeMonths FROM DiscountRule r
			JOIN DiscountRuleVersion v ON v.tenantId = r.tenantId AND v.id = r.currentVersionId
			JOIN DiscountRuleVersionCourse c ON c.tenantId = v.tenantId AND c.versionId = v.id
			WHERE r.tenantId = :tenantId AND r.id IN :ruleIds AND r.archivedAt IS NULL AND c.role = :role""")
	List<Object[]> findRewardWindowsUnchecked(@Param("tenantId") UUID tenantId,
			@Param("ruleIds") Collection<UUID> ruleIds, @Param("role") DiscountCourseRole role);

}
