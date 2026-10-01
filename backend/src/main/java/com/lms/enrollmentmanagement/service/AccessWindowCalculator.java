package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.domain.CourseAccessPolicy;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.EnrollmentAccessExtension;
import com.lms.enrollmentmanagement.domain.TenantAccessPolicy;
import com.lms.enrollmentmanagement.repository.CourseAccessPolicyRepository;
import com.lms.enrollmentmanagement.repository.EnrollmentAccessExtensionRepository;
import com.lms.enrollmentmanagement.repository.TenantAccessPolicyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The ONE implementation of an enrollment access window (Wave 10,
 * {@code AccessPolicyApi}): {@code effectiveExpiresAt = max(access_expires_at,
 * newest extension)}, {@code accessEndsAt = effectiveExpiresAt + grace}, where
 * grace is the course override if set, else the tenant setting, else 0 (product
 * owner decision D3). Lifetime enrollments ({@code access_expires_at} null)
 * have no window at all. Every access-currency read in this module -
 * {@code resolveAccessState}, roster/attendance/exam lists - goes through here,
 * so they can never disagree.
 */
@Component
public class AccessWindowCalculator {

	/** One enrollment window. Null instants mean lifetime access. */
	public record AccessWindow(Instant baseExpiresAt, Instant effectiveExpiresAt, int gracePeriodDays,
			Instant accessEndsAt, boolean extended) {

		public boolean isOpen(Instant now) {
			return accessEndsAt == null || now.isBefore(accessEndsAt);
		}

		public boolean inGracePeriod(Instant now) {
			return effectiveExpiresAt != null && !now.isBefore(effectiveExpiresAt) && now.isBefore(accessEndsAt);
		}

	}

	private final EnrollmentAccessExtensionRepository extensionRepository;

	private final TenantAccessPolicyRepository tenantPolicyRepository;

	private final CourseAccessPolicyRepository coursePolicyRepository;

	public AccessWindowCalculator(EnrollmentAccessExtensionRepository extensionRepository,
			TenantAccessPolicyRepository tenantPolicyRepository, CourseAccessPolicyRepository coursePolicyRepository) {
		this.extensionRepository = extensionRepository;
		this.tenantPolicyRepository = tenantPolicyRepository;
		this.coursePolicyRepository = coursePolicyRepository;
	}

	public AccessWindow windowFor(Enrollment enrollment) {
		return windowsFor(List.of(enrollment)).get(enrollment.getId());
	}

	/** Batch form - one query each for extensions, course policies and the tenant policy. */
	public Map<UUID, AccessWindow> windowsFor(Collection<Enrollment> enrollments) {
		Map<UUID, AccessWindow> windows = new HashMap<>();
		if (enrollments.isEmpty()) {
			return windows;
		}
		Map<UUID, Instant> newestExtension = extensionRepository
			.findByEnrollmentIds(enrollments.stream().map(Enrollment::getId).toList())
			.stream()
			.collect(Collectors.toMap(EnrollmentAccessExtension::getEnrollmentId,
					EnrollmentAccessExtension::getNewExpiresAt, (a, b) -> a.isAfter(b) ? a : b));
		Set<UUID> courseIds = enrollments.stream().map(Enrollment::getCourseId).collect(Collectors.toSet());
		Map<UUID, CourseAccessPolicy> coursePolicies = coursePolicyRepository.findByCourseIds(courseIds)
			.stream()
			.collect(Collectors.toMap(CourseAccessPolicy::getCourseId, Function.identity()));
		int tenantGrace = tenantGracePeriodDays();
		for (Enrollment enrollment : enrollments) {
			CourseAccessPolicy coursePolicy = coursePolicies.get(enrollment.getCourseId());
			int grace = coursePolicy != null && coursePolicy.getGracePeriodDays() != null
					? coursePolicy.getGracePeriodDays() : tenantGrace;
			windows.put(enrollment.getId(), window(enrollment.getAccessExpiresAt(),
					newestExtension.get(enrollment.getId()), grace));
		}
		return windows;
	}

	/** Lineage-current AND inside its window. */
	public boolean isCurrentlyActive(Enrollment enrollment, AccessWindow window, Instant now) {
		return enrollment.getSupersededAt() == null && window.isOpen(now);
	}

	public int tenantGracePeriodDays() {
		return tenantPolicyRepository.findForCurrentTenant().map(TenantAccessPolicy::getGracePeriodDays).orElse(0);
	}

	static AccessWindow window(Instant baseExpiresAt, Instant newestExtension, int gracePeriodDays) {
		if (baseExpiresAt == null) {
			return new AccessWindow(null, null, gracePeriodDays, null, false);
		}
		boolean extended = newestExtension != null && newestExtension.isAfter(baseExpiresAt);
		Instant effective = extended ? newestExtension : baseExpiresAt;
		return new AccessWindow(baseExpiresAt, effective, gracePeriodDays,
				effective.plus(Duration.ofDays(gracePeriodDays)), extended);
	}

}
