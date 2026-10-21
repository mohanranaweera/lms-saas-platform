package com.lms.enrollmentmanagement.service;

import com.lms.common.error.DeviceNotAuthorizedException;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.ModulePriceInfo;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.LessonAccessApi;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDeniedException;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import com.lms.enrollmentmanagement.domain.FreeCard;
import com.lms.enrollmentmanagement.repository.ContentEntitlementRepository;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.enrollmentmanagement.repository.FreeCardRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Implements {@link LessonAccessApi} - see its javadoc and {@link
 * LessonAccessRules} for the rules and precedence. This class only loads the
 * facts, with a constant number of tenant-scoped reads per course: catalog
 * info, lesson policies, linked parent ids + one participation existence
 * check, ONE legacy course evaluation ({@link
 * AccessPolicyService#evaluateLegacyCoverage}), unrevoked entitlements, pending
 * coverage keys, module prices (only when needed) and one device check.
 *
 * <p>Deliberately NOT {@code @Transactional}: the player variant must not mark
 * its caller's transaction rollback-only when it throws (see {@link
 * AccessPolicyService#requireStudentCourseAccessOnDevice}); every read joins
 * the caller's transaction or runs in its own.
 */
@Service
public class LessonAccessService implements LessonAccessApi {

	private final CourseLookupApi courseLookupApi;

	private final CourseParticipationRepository participationRepository;

	private final ContentEntitlementRepository entitlementRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final AccessPolicyService accessPolicyService;

	private final FreeCardRepository freeCardRepository;

	public LessonAccessService(CourseLookupApi courseLookupApi, CourseParticipationRepository participationRepository,
			ContentEntitlementRepository entitlementRepository, PaymentStatusApi paymentStatusApi,
			AccessPolicyService accessPolicyService, FreeCardRepository freeCardRepository) {
		this.courseLookupApi = courseLookupApi;
		this.participationRepository = participationRepository;
		this.entitlementRepository = entitlementRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.accessPolicyService = accessPolicyService;
		this.freeCardRepository = freeCardRepository;
	}

	/** One course's lessons (module, then lesson order) with their decisions. */
	public record CourseLessonAccess(UUID courseId, Optional<CourseCatalogInfo> course, List<LessonPolicy> lessons,
			Map<UUID, LessonAccessDecision> decisions) {

	}

	@Override
	public Map<UUID, LessonAccessDecision> evaluate(UUID courseId, Collection<UUID> lessonIds) {
		CourseLessonAccess access = evaluateCourse(callerId(), courseId, null);
		Map<UUID, LessonAccessDecision> result = new LinkedHashMap<>();
		for (UUID lessonId : lessonIds) {
			result.put(lessonId, access.decisions().getOrDefault(lessonId,
					LessonAccessRules.unavailable(courseId, lessonId)));
		}
		return result;
	}

	/** Every lesson of {@code courseId} for the calling student (content tree / live classes). */
	public CourseLessonAccess evaluateAllForCaller(UUID courseId) {
		return evaluateCourse(callerId(), courseId, null);
	}

	@Override
	public LessonAccessDecision requireLessonAccess(UUID courseId, UUID lessonId,
			Supplier<? extends RuntimeException> notVisible) {
		LessonAccessDecision decision = evaluate(courseId, List.of(lessonId)).get(lessonId);
		return enforce(decision, notVisible);
	}

	@Override
	public LessonAccessDecision requireLessonAccessOnDevice(UUID studentId, UUID courseId, UUID lessonId,
			UUID deviceId, Supplier<? extends RuntimeException> notVisible) {
		if (deviceId == null) {
			throw notVisible.get();
		}
		LessonAccessDecision decision = evaluateCourse(studentId, courseId, deviceId).decisions()
			.getOrDefault(lessonId, LessonAccessRules.unavailable(courseId, lessonId));
		return enforce(decision, notVisible);
	}

	@Override
	public boolean isCallerParticipant(UUID courseId) {
		return isParticipantOrLegacyEnrolled(callerId(), courseId);
	}

	/** {@link #isCallerParticipant} for an explicit, already server-resolved student (ADR-030 staff flows). */
	boolean isParticipantOrLegacyEnrolled(UUID studentId, UUID courseId) {
		return isParticipant(studentId, courseId)
				|| accessPolicyService.evaluateLegacyCoverage(studentId, courseId) != AccessOutcome.NOT_ENROLLED;
	}

	static LessonAccessDecision enforce(LessonAccessDecision decision,
			Supplier<? extends RuntimeException> notVisible) {
		switch (decision.status()) {
			case ALLOWED -> {
				return decision;
			}
			case UNAVAILABLE, NOT_ENROLLED -> throw notVisible.get();
			case DEVICE_NOT_AUTHORIZED -> throw new DeviceNotAuthorizedException(
					"This course can only be opened on a limited number of devices, and this device is not one of them.");
			default -> throw new LessonAccessDeniedException(decision);
		}
	}

	/**
	 * @param deviceId {@code null} = the caller's own session device (as the
	 * legacy course guard); non-null = an explicit, server-held player device.
	 */
	CourseLessonAccess evaluateCourse(UUID studentId, UUID courseId, UUID deviceId) {
		Optional<CourseCatalogInfo> info = courseLookupApi.findCatalogInfo(courseId);
		boolean available = info.isPresent() && !"DRAFT".equals(info.get().status());
		if (!available) {
			return new CourseLessonAccess(courseId, info, List.of(), Map.of());
		}
		List<LessonPolicy> lessons = courseLookupApi.findLessonPolicies(courseId);
		Instant now = Instant.now();

		AccessOutcome legacy = accessPolicyService.evaluateLegacyCoverage(studentId, courseId);
		boolean enrolled = legacy != AccessOutcome.NOT_ENROLLED || isParticipant(studentId, courseId);
		boolean deviceAuthorized = !enrolled || accessPolicyService.isDeviceAuthorizedForCourse(studentId, courseId,
				deviceId);

		Set<String> activeKeys = Set.of();
		Set<String> pendingKeys = Set.of();
		Map<UUID, Object> modulePrices = Map.of();
		boolean freePricing = "FREE".equals(info.get().pricingModel());
		boolean legacyCovered = legacy == AccessOutcome.ALLOWED;
		if (enrolled && !lessons.isEmpty()) {
			Set<String> keys = entitlementRepository.findUnrevokedByStudentIdAndCourseId(studentId, courseId)
				.stream()
				.filter(entitlement -> entitlement.isActiveAt(now))
				.map(ContentEntitlement::getCoverageKey)
				.collect(Collectors.toCollection(HashSet::new));
			// ADR-030 (F4): an effective Free Card of THIS course adds its own
			// MONTHLY keys (never a linked parent's card, never MODULE/LESSON).
			for (FreeCard card : freeCardRepository.findUnrevokedByStudentIdAndCourseId(studentId, courseId)) {
				if (card.isEffectiveAt(now)) {
					keys.addAll(card.coveredMonthKeys());
				}
			}
			activeKeys = keys;
			if (needsPaymentFacts(lessons, freePricing, legacyCovered)) {
				pendingKeys = paymentStatusApi.findPendingCoverageKeys(studentId, courseId);
			}
			if (needsModulePrices(lessons, freePricing, legacyCovered)) {
				Map<UUID, Object> prices = new HashMap<>();
				for (ModulePriceInfo module : courseLookupApi.findModulePrices(courseId)) {
					if (module.price() != null) {
						prices.put(module.moduleId(), module.price());
					}
				}
				modulePrices = prices;
			}
		}

		LessonAccessRules.CourseFacts facts = new LessonAccessRules.CourseFacts(courseId, true, freePricing,
				"ONE_TIME".equals(info.get().pricingModel()), enrolled, deviceAuthorized, legacyCovered, activeKeys,
				pendingKeys, modulePrices);
		Map<UUID, LessonAccessDecision> decisions = new LinkedHashMap<>();
		for (LessonPolicy lesson : lessons) {
			decisions.put(lesson.lessonId(), LessonAccessRules.decide(facts, lesson, now));
		}
		return new CourseLessonAccess(courseId, info, lessons, decisions);
	}

	/**
	 * Direct participation, or participation in a parent course that links
	 * {@code courseId} (D13, one level: the parent list is the direct parents
	 * only - never transitive).
	 */
	boolean isParticipant(UUID studentId, UUID courseId) {
		List<UUID> candidates = new ArrayList<>();
		candidates.add(courseId);
		candidates.addAll(courseLookupApi.findLinkedParentCourseIds(courseId));
		return participationRepository.existsByStudentIdAndCourseIdIn(studentId, candidates);
	}

	private static boolean needsPaymentFacts(List<LessonPolicy> lessons, boolean freePricing, boolean legacyCovered) {
		return lessons.stream().anyMatch(lesson -> lesson.paymentType() == LessonPaymentType.PAID
				|| (lesson.paymentType() != LessonPaymentType.FREE && !freePricing && !legacyCovered));
	}

	private static boolean needsModulePrices(List<LessonPolicy> lessons, boolean freePricing,
			boolean legacyCovered) {
		return !freePricing && !legacyCovered && lessons.stream()
			.anyMatch(lesson -> lesson.paymentType() != LessonPaymentType.FREE
					&& lesson.paymentType() != LessonPaymentType.PAID && lesson.billingYear() == null);
	}

	private static UUID callerId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

}
