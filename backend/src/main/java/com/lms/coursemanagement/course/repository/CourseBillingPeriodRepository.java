package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseBillingPeriod;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per ADR-006. See {@link CourseRepository}'s javadoc for why
 * every custom finder below is a {@code Specification}-backed default method
 * rather than a derived-query method. {@code course_billing_period} is
 * append-only (V39) - no delete-shaped method is exposed here at all (unlike
 * {@link CoursePriceHistoryRepository}, this interface simply never declares
 * one, since {@link TenantAwareRepository} itself is the only supertype and
 * none of its delete methods are ever called in practice - callers should
 * still never invoke them; a future reviewer adding one here is the actual
 * risk this comment flags).
 */
public interface CourseBillingPeriodRepository extends TenantAwareRepository<CourseBillingPeriod, UUID> {

	/** The single OPEN ({@code effective_to IS NULL}) period for a billing configuration, if any. */
	default Optional<CourseBillingPeriod> findCurrentOpenByBillingConfigurationId(UUID billingConfigurationId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("billingConfigurationId"), billingConfigurationId),
				cb.isNull(root.get("effectiveTo"))));
	}

	default Page<CourseBillingPeriod> findByBillingConfigurationId(UUID billingConfigurationId, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("billingConfigurationId"), billingConfigurationId),
				pageable);
	}

	/**
	 * Batched sibling of {@link #findCurrentOpenByBillingConfigurationId(UUID)}
	 * - resolves the single OPEN period (if any) for every billing
	 * configuration id given, in one query, so a multi-course read path never
	 * calls the singular finder once per course in a loop. Added for {@code
	 * CourseCheckoutAmountResolver#resolveBatch}.
	 */
	default List<CourseBillingPeriod> findCurrentOpenByBillingConfigurationIdIn(
			Collection<UUID> billingConfigurationIds) {
		if (billingConfigurationIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("billingConfigurationId").in(billingConfigurationIds),
				cb.isNull(root.get("effectiveTo"))));
	}

}
