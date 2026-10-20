package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.DeviceNotAuthorizedException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.LessonResourceType;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDeniedException;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import com.lms.enrollmentmanagement.domain.ContentEntitlement;
import com.lms.enrollmentmanagement.repository.ContentEntitlementRepository;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * KLASS-T4b: {@link LessonAccessService} fact loading - entitlement validity
 * (revoked / valid_until), linked-course rules (D13: SOURCE course rules, no
 * bypass through the parent's payment), the device check on entitlement-based
 * access and the guard exception mapping.
 */
@ExtendWith(MockitoExtension.class)
class LessonAccessServiceTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID STUDENT = UUID.randomUUID();

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID PARENT = UUID.randomUUID();

	private static final UUID MODULE = UUID.randomUUID();

	private static final UUID LESSON = UUID.randomUUID();

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private CourseParticipationRepository participationRepository;

	@Mock
	private ContentEntitlementRepository entitlementRepository;

	@Mock
	private PaymentStatusApi paymentStatusApi;

	@Mock
	private AccessPolicyService accessPolicyService;

	private LessonAccessService service;

	@BeforeEach
	void setUp() {
		service = new LessonAccessService(courseLookupApi, participationRepository, entitlementRepository,
				paymentStatusApi, accessPolicyService);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(STUDENT, TENANT, "STUDENT", UUID.randomUUID()));
		lenient().when(courseLookupApi.findLinkedParentCourseIds(any())).thenReturn(List.of());
		lenient().when(accessPolicyService.isDeviceAuthorizedForCourse(any(), any(), any())).thenReturn(true);
		lenient().when(paymentStatusApi.findPendingCoverageKeys(any(), any())).thenReturn(Set.of());
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private void course(UUID courseId, String pricingModel, LessonPaymentType type, Integer year, Integer month) {
		when(courseLookupApi.findCatalogInfo(courseId)).thenReturn(Optional.of(new CourseCatalogInfo(courseId,
				"Course", UUID.randomUUID(), pricingModel, "PUBLIC", false, true, null, null, null, null, "LKR")));
		when(courseLookupApi.findLessonPolicies(courseId)).thenReturn(List.of(new LessonPolicy(LESSON, MODULE,
				"Module", 1, "Lesson", null, 1, LessonResourceType.DOCUMENT, type, null, null, null, year, month)));
	}

	private static ContentEntitlement entitlement(String key, Instant validUntil, Instant revokedAt) throws Exception {
		Constructor<ContentEntitlement> constructor = ContentEntitlement.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		ContentEntitlement entitlement = constructor.newInstance();
		ReflectionTestUtils.setField(entitlement, "coverageKey", key);
		ReflectionTestUtils.setField(entitlement, "validUntil", validUntil);
		ReflectionTestUtils.setField(entitlement, "revokedAt", revokedAt);
		ReflectionTestUtils.setField(entitlement, "grantedAt", Instant.now().minus(Duration.ofDays(40)));
		return entitlement;
	}

	private LessonAccessDecision evaluateLesson(UUID courseId) {
		return service.evaluate(courseId, List.of(LESSON)).get(LESSON);
	}

	@Test
	void freeParticipantWithoutCoverageGetsPaymentRequiredForAClassFeeLesson() {
		course(COURSE, "MONTHLY", LessonPaymentType.COVERED_BY_CLASS_FEES, 2026, 10);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.NOT_ENROLLED);
		when(participationRepository.existsByStudentIdAndCourseIdIn(eq(STUDENT), anyCollection())).thenReturn(true);
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE)).thenReturn(List.of());

		LessonAccessDecision decision = evaluateLesson(COURSE);

		assertThat(decision.status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
		assertThat(decision.requiredPurchase().scope()).isEqualTo("MONTHLY");
	}

	@Test
	void validMonthlyEntitlementAllowsAndExpiredValidUntilDoesNot() throws Exception {
		course(COURSE, "MONTHLY", LessonPaymentType.COVERED_BY_CLASS_FEES, 2026, 10);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.NOT_ENROLLED);
		when(participationRepository.existsByStudentIdAndCourseIdIn(eq(STUDENT), anyCollection())).thenReturn(true);
		String key = CoverageKeys.monthly(COURSE, 2026, 10);

		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE))
			.thenReturn(List.of(entitlement(key, Instant.now().plus(Duration.ofDays(5)), null)));
		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.ALLOWED);

		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE))
			.thenReturn(List.of(entitlement(key, Instant.now().minus(Duration.ofDays(1)), null)));
		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);

		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE))
			.thenReturn(List.of(entitlement(key, null, null)));
		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.ALLOWED);
	}

	@Test
	void revokedEntitlementNeverCountsEvenIfReturned() throws Exception {
		course(COURSE, "MONTHLY", LessonPaymentType.PAID, null, null);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.ALLOWED);
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE))
			.thenReturn(List.of(entitlement(CoverageKeys.lesson(LESSON), null, Instant.now().minusSeconds(5))));

		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
	}

	@Test
	void pendingCoverageKeyGivesPaymentPending() {
		course(COURSE, "MONTHLY", LessonPaymentType.COVERED_BY_CLASS_FEES, 2026, 10);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.EXPIRED);
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE)).thenReturn(List.of());
		when(paymentStatusApi.findPendingCoverageKeys(STUDENT, COURSE))
			.thenReturn(Set.of(CoverageKeys.monthly(COURSE, 2026, 10)));

		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.PAYMENT_PENDING);
	}

	@Test
	void linkedSourceLessonUsesSourceRulesAndParentPaymentNeverUnlocksIt() {
		// The student only participates in (and has paid for) the PARENT course.
		course(COURSE, "MONTHLY", LessonPaymentType.COVERED_BY_CLASS_FEES, 2026, 10);
		when(courseLookupApi.findLinkedParentCourseIds(COURSE)).thenReturn(List.of(PARENT));
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.NOT_ENROLLED);
		when(participationRepository.existsByStudentIdAndCourseIdIn(STUDENT, List.of(COURSE, PARENT)))
			.thenReturn(true);
		// Entitlements are read for the SOURCE course only; the parent's MONTHLY
		// coverage key carries the parent id and can never match a source lesson.
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE)).thenReturn(List.of());

		LessonAccessDecision decision = evaluateLesson(COURSE);

		assertThat(decision.status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
		assertThat(decision.courseId()).isEqualTo(COURSE);
		assertThat(decision.requiredPurchase().courseId()).isEqualTo(COURSE);
		verify(accessPolicyService, never()).evaluateLegacyCoverage(STUDENT, PARENT);
		verify(entitlementRepository, never()).findUnrevokedByStudentIdAndCourseId(STUDENT, PARENT);
	}

	@Test
	void notEnrolledWhenNeitherSourceNorDirectParentParticipationExists() {
		course(COURSE, "FREE", LessonPaymentType.FREE, null, null);
		when(courseLookupApi.findLinkedParentCourseIds(COURSE)).thenReturn(List.of(PARENT));
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.NOT_ENROLLED);
		when(participationRepository.existsByStudentIdAndCourseIdIn(STUDENT, List.of(COURSE, PARENT)))
			.thenReturn(false);

		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.NOT_ENROLLED);
		assertThatThrownBy(() -> service.requireLessonAccess(COURSE, LESSON, () -> new NotFoundException("x")))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void unknownLessonOrDraftCourseIsUnavailable() {
		when(courseLookupApi.findCatalogInfo(COURSE)).thenReturn(Optional.empty());
		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.UNAVAILABLE);

		UUID draft = UUID.randomUUID();
		when(courseLookupApi.findCatalogInfo(draft)).thenReturn(Optional.of(new CourseCatalogInfo(draft, "Draft",
				UUID.randomUUID(), "FREE", "DRAFT", false, true, null, null, null, null, "LKR")));
		assertThat(evaluateLesson(draft).status()).isEqualTo(LessonAccessStatus.UNAVAILABLE);
	}

	@Test
	void requireLessonAccessMapsPaymentDenialToTypedForbidden() {
		course(COURSE, "MONTHLY", LessonPaymentType.PAID, null, null);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.ALLOWED);
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE)).thenReturn(List.of());

		assertThatThrownBy(() -> service.requireLessonAccess(COURSE, LESSON, () -> new NotFoundException("x")))
			.isInstanceOfSatisfying(LessonAccessDeniedException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_REQUIRED");
				assertThat(ex.getDecision().requiredPurchase().scope()).isEqualTo("LESSON");
			});
	}

	@Test
	void deviceCheckAppliesToEntitlementBasedAccess() throws Exception {
		course(COURSE, "MONTHLY", LessonPaymentType.PAID, null, null);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.NOT_ENROLLED);
		when(participationRepository.existsByStudentIdAndCourseIdIn(eq(STUDENT), anyCollection())).thenReturn(true);
		when(accessPolicyService.isDeviceAuthorizedForCourse(STUDENT, COURSE, null)).thenReturn(false);
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE))
			.thenReturn(List.of(entitlement(CoverageKeys.lesson(LESSON), null, null)));

		assertThat(evaluateLesson(COURSE).status()).isEqualTo(LessonAccessStatus.DEVICE_NOT_AUTHORIZED);
		assertThatThrownBy(() -> service.requireLessonAccess(COURSE, LESSON, () -> new NotFoundException("x")))
			.isInstanceOf(DeviceNotAuthorizedException.class);
	}

	@Test
	void playerVariantChecksTheExplicitDeviceAndRefusesAMissingOne() throws Exception {
		UUID device = UUID.randomUUID();
		course(COURSE, "MONTHLY", LessonPaymentType.PAID, null, null);
		when(accessPolicyService.evaluateLegacyCoverage(STUDENT, COURSE)).thenReturn(AccessOutcome.NOT_ENROLLED);
		when(participationRepository.existsByStudentIdAndCourseIdIn(eq(STUDENT), anyCollection())).thenReturn(true);
		when(accessPolicyService.isDeviceAuthorizedForCourse(STUDENT, COURSE, device)).thenReturn(true);
		when(entitlementRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE))
			.thenReturn(List.of(entitlement(CoverageKeys.lesson(LESSON), null, null)));

		assertThat(service.requireLessonAccessOnDevice(STUDENT, COURSE, LESSON, device,
				() -> new NotFoundException("x")).status()).isEqualTo(LessonAccessStatus.ALLOWED);
		assertThatThrownBy(() -> service.requireLessonAccessOnDevice(STUDENT, COURSE, LESSON, null,
				() -> new NotFoundException("x"))).isInstanceOf(NotFoundException.class);
	}

}
