package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import com.lms.enrollmentmanagement.repository.ContentEntitlementRepository;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Implements {@link ContentCoverageApi} (KLASS-T4c) for the payment composer.
 * Reads only - tenant-scoped repositories and the calling student; the lock
 * method takes the same advisory locks the grant path takes.
 */
@Service
public class ContentCoverageService implements ContentCoverageApi {

	private final CourseParticipationRepository participationRepository;

	private final ContentEntitlementRepository entitlementRepository;

	private final AccessPolicyService accessPolicyService;

	private final LessonAccessService lessonAccessService;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	public ContentCoverageService(CourseParticipationRepository participationRepository,
			ContentEntitlementRepository entitlementRepository, AccessPolicyService accessPolicyService,
			LessonAccessService lessonAccessService, CourseLookupApi courseLookupApi, TenantContext tenantContext) {
		this.participationRepository = participationRepository;
		this.entitlementRepository = entitlementRepository;
		this.accessPolicyService = accessPolicyService;
		this.lessonAccessService = lessonAccessService;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
	}

	@Override
	public Set<UUID> findCallerCourseIds() {
		return findCourseIds(callerId());
	}

	@Override
	public boolean isCallerParticipant(UUID courseId) {
		return lessonAccessService.isCallerParticipant(courseId);
	}

	@Override
	public CallerCoverage coverageForCaller(UUID courseId) {
		return coverageFor(callerId(), courseId);
	}

	@Override
	public void lockCallerCoverage(Set<String> coverageKeys) {
		lockCoverage(callerId(), coverageKeys);
	}

	@Override
	public Set<UUID> findCourseIds(UUID studentId) {
		Set<UUID> direct = participationRepository.findCourseIdsByStudentId(studentId);
		Set<UUID> result = new HashSet<>(direct);
		for (UUID courseId : direct) {
			result.addAll(courseLookupApi.findLinkedSourceCourseIds(courseId));
		}
		return result;
	}

	@Override
	public boolean isParticipant(UUID studentId, UUID courseId) {
		return lessonAccessService.isParticipantOrLegacyEnrolled(studentId, courseId);
	}

	/** Payment-backed coverage only - Free Card keys are never added here (ADR-030 F9). */
	@Override
	public CallerCoverage coverageFor(UUID studentId, UUID courseId) {
		Instant now = Instant.now();
		Set<String> active = entitlementRepository.findUnrevokedByStudentIdAndCourseId(studentId, courseId)
			.stream()
			.filter(entitlement -> entitlement.isActiveAt(now))
			.map(ContentEntitlement::getCoverageKey)
			.collect(Collectors.toSet());
		boolean legacy = accessPolicyService.evaluateLegacyCoverage(studentId, courseId) == AccessOutcome.ALLOWED;
		return new CallerCoverage(active, legacy);
	}

	@Override
	public void lockCoverage(UUID studentId, Set<String> coverageKeys) {
		UUID tenantId = tenantContext.getTenantId();
		for (String key : new TreeSet<>(coverageKeys)) {
			entitlementRepository.acquireCoverageLock(CoverageKeys.lockKey(tenantId, studentId, key));
		}
	}

	private static UUID callerId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

}
