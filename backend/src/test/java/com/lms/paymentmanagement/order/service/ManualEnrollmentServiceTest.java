package com.lms.paymentmanagement.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.ManualEnrollmentResult;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Mockito-only unit coverage for {@link ManualEnrollmentService} (Wave 3,
 * Student actions - staff "enroll student in course"): the mandatory-reason
 * gate, the already-enrolled/CUSTOM-pricing rejections, and - the bug this
 * suite exists to pin down after being caught by {@code
 * Wave3StudentTeacherActionsIntegrationTest} - that a ledger entry is always
 * written for the CONFIRMED payment, exactly like every other
 * CONFIRMED-payment path (ADR-015's precedent).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManualEnrollmentServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private StudentOrderRepository studentOrderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private EnrollmentActivationApi enrollmentActivationApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private LedgerEntryApi ledgerEntryApi;

	@Mock
	private StudentLookupApi studentLookupApi;

	private ManualEnrollmentService service;

	@BeforeEach
	void setUp() {
		service = new ManualEnrollmentService(studentOrderRepository, paymentRepository, courseLookupApi,
				tenantContext, enrollmentAccessApi, enrollmentActivationApi, permissionCheckService, auditLogApi,
				ledgerEntryApi, studentLookupApi);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TENANT_ADMIN", null));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void rejectsABlankReasonBeforeAnyLookup() {
		assertThatThrownBy(() -> service.grantEnrollment(STUDENT_ID, COURSE_ID, "  "))
			.isInstanceOf(ConflictException.class);

		verify(courseLookupApi, never()).getResolvedCheckoutAmount(any());
	}

	@Test
	void rejectsANonexistentCourse() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.grantEnrollment(STUDENT_ID, COURSE_ID, "reason"))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void rejectsAnUnpublishedCourse() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(BigDecimal.TEN, "USD", null, false, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.grantEnrollment(STUDENT_ID, COURSE_ID, "reason"))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void rejectsACustomPricedCourse() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(null, "USD", null, true, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);

		assertThatThrownBy(() -> service.grantEnrollment(STUDENT_ID, COURSE_ID, "reason"))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void rejectsAnAlreadyActivelyEnrolledStudent() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(BigDecimal.TEN, "USD", null, false, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));

		assertThatThrownBy(() -> service.grantEnrollment(STUDENT_ID, COURSE_ID, "reason"))
			.isInstanceOf(ConflictException.class);

		verify(studentOrderRepository, never()).save(any());
	}

	/** The regression this suite was added to pin down - see class javadoc. */
	@Test
	void aSuccessfulGrantWritesTheOrderThePaymentAndTheLedgerEntryAndActivatesEnrollment() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(new BigDecimal("50.00"), "USD", null, false, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		when(studentOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(paymentRepository.save(any())).thenAnswer(inv -> {
			Payment payment = inv.getArgument(0);
			setId(payment, UUID.randomUUID());
			return payment;
		});

		ManualEnrollmentResult result = service.grantEnrollment(STUDENT_ID, COURSE_ID, "scholarship");

		assertThat(result.amount()).isEqualByComparingTo("50.00");
		verify(ledgerEntryApi, times(1)).recordPaymentConfirmed(any(), any(), any());
		verify(enrollmentActivationApi, times(1)).fromApprovedManualEvidence(any(), any(), org.mockito.ArgumentMatchers.eq(STUDENT_ID),
				org.mockito.ArgumentMatchers.eq(COURSE_ID));
		verify(auditLogApi, times(1)).record(any());
	}

	/**
	 * Wave 3 fix-pass (security review, Bug 2 - "enroll/revoke audit entries
	 * invisible on the student's own Activity tab"): {@code grantEnrollment}
	 * now writes a SECOND audit row, in the same transaction, targeting
	 * {@code student_profile}/the student's own profile id (resolved from the
	 * opaque cross-domain {@code studentId} via {@link
	 * StudentLookupApi#getStudentSummariesByUserId}) - in addition to, never
	 * instead of, the pre-existing {@code payment}-targeted row {@link
	 * #aSuccessfulGrantWritesTheOrderThePaymentAndTheLedgerEntryAndActivatesEnrollment}
	 * already proves. Both rows carry the same {@code action}/{@code reason}.
	 */
	@Test
	void aSuccessfulGrantWritesASecondAuditRowTargetingTheResolvedStudentProfileId() {
		UUID studentProfileId = UUID.randomUUID();
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(new BigDecimal("50.00"), "USD", null, false, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		when(studentOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(paymentRepository.save(any())).thenAnswer(inv -> {
			Payment payment = inv.getArgument(0);
			setId(payment, UUID.randomUUID());
			return payment;
		});
		when(studentLookupApi.getStudentSummariesByUserId(List.of(STUDENT_ID)))
			.thenReturn(List.of(new StudentSummary(studentProfileId, STUDENT_ID, "Test Student", "student@example.test")));

		service.grantEnrollment(STUDENT_ID, COURSE_ID, "scholarship");

		org.mockito.ArgumentCaptor<com.lms.auditlogmanagement.api.AuditLogEntry> captor = org.mockito.ArgumentCaptor
			.forClass(com.lms.auditlogmanagement.api.AuditLogEntry.class);
		verify(auditLogApi, times(2)).record(captor.capture());
		var entries = captor.getAllValues();
		assertThat(entries).extracting(com.lms.auditlogmanagement.api.AuditLogEntry::targetEntity)
			.containsExactlyInAnyOrder("payment", "student_profile");
		var studentProfileEntry = entries.stream()
			.filter(e -> "student_profile".equals(e.targetEntity()))
			.findFirst()
			.orElseThrow();
		assertThat(studentProfileEntry.targetId()).isEqualTo(studentProfileId);
		assertThat(studentProfileEntry.action()).isEqualTo("enrollment.manually_granted");
		assertThat(studentProfileEntry.reason()).isEqualTo("scholarship");
	}

	@Test
	void anActivationRefusalIsLoggedButDoesNotFailTheRequestOrSkipTheLedgerWrite() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(new BigDecimal("50.00"), "USD", null, false, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		when(studentOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(paymentRepository.save(any())).thenAnswer(inv -> {
			Payment payment = inv.getArgument(0);
			setId(payment, UUID.randomUUID());
			return payment;
		});
		org.mockito.Mockito.doThrow(new IllegalStateException("no matching reactivation request"))
			.when(enrollmentActivationApi)
			.fromApprovedManualEvidence(any(), any(), any(), any());

		ManualEnrollmentResult result = service.grantEnrollment(STUDENT_ID, COURSE_ID, "scholarship");

		assertThat(result).isNotNull();
		verify(ledgerEntryApi, times(1)).recordPaymentConfirmed(any(), any(), any());
	}

	/**
	 * {@link Payment}'s id (inherited from {@code BaseEntity}) has no public
	 * setter - populated by Hibernate's UUIDv7 generator at real persist
	 * time. Reflection is used here purely to simulate that for this
	 * fully-mocked-repository unit test (the mocked {@code
	 * paymentRepository.save} does not itself run the real generator),
	 * mirroring {@code StudentServiceTest#setId}'s identical pattern.
	 */
	private static void setId(Payment payment, UUID id) {
		try {
			java.lang.reflect.Field field = com.lms.common.persistence.BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(payment, id);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
