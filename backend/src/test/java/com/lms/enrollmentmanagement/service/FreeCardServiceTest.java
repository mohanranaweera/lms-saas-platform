package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import com.lms.enrollmentmanagement.domain.CourseParticipationSource;
import com.lms.enrollmentmanagement.domain.FreeCard;
import com.lms.enrollmentmanagement.domain.FreeCardType;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.enrollmentmanagement.repository.FreeCardRepository;
import com.lms.enrollmentmanagement.service.FreeCardViews.FreeCardView;
import com.lms.enrollmentmanagement.service.FreeCardViews.IssueCommand;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.identityaccessservice.service.PermissionCheckServiceImpl;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/** ADR-030 (F1-F8): {@link FreeCardService} rules with mocked collaborators and the REAL permission matrix. */
@ExtendWith(MockitoExtension.class)
class FreeCardServiceTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID ADMIN = UUID.randomUUID();

	private static final UUID PROFILE = UUID.randomUUID();

	private static final UUID STUDENT = UUID.randomUUID();

	private static final UUID COURSE = UUID.randomUUID();

	@Mock
	private FreeCardRepository freeCardRepository;

	@Mock
	private CourseParticipationRepository participationRepository;

	@Mock
	private LessonAccessService lessonAccessService;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private StudentLookupApi studentLookupApi;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private TenantConfigApi tenantConfigApi;

	@Mock
	private TenantContext tenantContext;

	private FreeCardService service;

	private final LocalDate today = LocalDate.now(ZoneId.of("UTC"));

	@BeforeEach
	void setUp() {
		service = new FreeCardService(freeCardRepository, participationRepository, lessonAccessService,
				courseLookupApi, studentLookupApi, userProvisioningApi, new PermissionCheckServiceImpl(), auditLogApi,
				tenantConfigApi, tenantContext);
		as("TENANT_ADMIN");
		lenient().when(tenantContext.getTenantId()).thenReturn(TENANT);
		lenient().when(tenantConfigApi.resolveValue(any(), any(), anyString())).thenReturn(Optional.empty());
		lenient().when(studentLookupApi.resolveUserId(PROFILE)).thenReturn(Optional.of(STUDENT));
		lenient().when(freeCardRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
		lenient().when(freeCardRepository.save(any(FreeCard.class))).thenAnswer(invocation -> {
			FreeCard card = invocation.getArgument(0);
			if (card.getId() == null) {
				ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
			}
			return card;
		});
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void as(String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(ADMIN, TENANT, role, UUID.randomUUID()));
	}

	private void course(String pricingModel, boolean registrationEnabled) {
		when(courseLookupApi.findCatalogInfo(COURSE)).thenReturn(Optional.of(new CourseCatalogInfo(COURSE, "Python",
				UUID.randomUUID(), pricingModel, "PUBLIC", false, registrationEnabled, null, new BigDecimal("3000"),
				"LKR", UUID.randomUUID(), "LKR")));
	}

	private IssueCommand command(String type, LocalDate from, LocalDate to) {
		return new IssueCommand(PROFILE, COURSE, type, from, to, "scholarship", UUID.randomUUID());
	}

	private static void assertCode(Throwable thrown, HttpStatus status, String code) {
		assertThat(thrown).isInstanceOf(ApplicationException.class);
		assertThat(((ApplicationException) thrown).getHttpStatus()).isEqualTo(status);
		assertThat(((ApplicationException) thrown).getErrorCode()).isEqualTo(code);
	}

	@Test
	void issuesForAnExistingParticipantWithNominalValueAndAudit() {
		course("MONTHLY", true);
		when(lessonAccessService.isParticipantOrLegacyEnrolled(STUDENT, COURSE)).thenReturn(true);

		FreeCardView view = service.issue(command("full", today, today.plusMonths(1)));

		assertThat(view.cardType()).isEqualTo("FULL");
		assertThat(view.status()).isEqualTo("ACTIVE");
		assertThat(view.enrollmentCreated()).isFalse();
		assertThat(view.nominalMonthlyAmount()).isEqualByComparingTo("3000.00");
		assertThat(view.coveredMonths()).hasSizeBetween(2, 2);
		assertThat(view.nominalValue()).isEqualByComparingTo("6000.00");
		assertThat(view.timezone()).isEqualTo("UTC");
		verify(freeCardRepository).acquireLock("freecard:" + TENANT + ":" + STUDENT + ":" + COURSE);
		verify(participationRepository, never()).insertIfAbsent(any(), any(), any(), any());
		ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(audit.capture());
		assertThat(audit.getValue().action()).isEqualTo("free_card.issued");
		assertThat(audit.getValue().actorId()).isEqualTo(ADMIN);
		assertThat(audit.getValue().reason()).isEqualTo("scholarship");
	}

	@Test
	void nonParticipantGetsAStaffGrantParticipationAuditedSeparately() throws Exception {
		course("MONTHLY", true);
		when(lessonAccessService.isParticipantOrLegacyEnrolled(STUDENT, COURSE)).thenReturn(false);
		when(participationRepository.insertIfAbsent(eq(STUDENT), eq(COURSE),
				eq(CourseParticipationSource.STAFF_GRANT), any()))
			.thenReturn(1);
		Constructor<CourseParticipation> constructor = CourseParticipation.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		CourseParticipation participation = constructor.newInstance();
		ReflectionTestUtils.setField(participation, "id", UUID.randomUUID());
		when(participationRepository.findByStudentIdAndCourseId(STUDENT, COURSE)).thenReturn(Optional.of(participation));

		FreeCardView view = service.issue(command("FULL", today, today));

		assertThat(view.enrollmentCreated()).isTrue();
		ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi, org.mockito.Mockito.times(2)).record(audit.capture());
		assertThat(audit.getAllValues().get(0).action()).isEqualTo("course.participation_created");
		assertThat(audit.getAllValues().get(0).metadata()).containsEntry("source", "STAFF_GRANT");
		assertThat(audit.getAllValues().get(1).action()).isEqualTo("free_card.issued");
	}

	@Test
	void closedRegistrationRefusesANewParticipant() {
		course("MONTHLY", false);
		when(lessonAccessService.isParticipantOrLegacyEnrolled(STUDENT, COURSE)).thenReturn(false);

		assertThatThrownBy(() -> service.issue(command("FULL", today, today)))
			.isInstanceOf(RegistrationClosedException.class);
		verify(freeCardRepository, never()).save(any());
	}

	@Test
	void halfCardIsUnsupportedAndUnknownTypeIsAValidationError() {
		assertCode(catchThrowable(() -> service.issue(command("HALF", today, today))),
				HttpStatus.UNPROCESSABLE_CONTENT, FreeCardException.FREE_CARD_TYPE_UNSUPPORTED);
		assertCode(catchThrowable(() -> service.issue(command("QUARTER", today, today))),
				HttpStatus.UNPROCESSABLE_CONTENT, FreeCardException.VALIDATION_ERROR);
		verify(freeCardRepository, never()).save(any());
	}

	@Test
	void validityRules() {
		assertCode(catchThrowable(() -> FreeCardService.requireValidity(null, today, today)),
				HttpStatus.UNPROCESSABLE_CONTENT, FreeCardException.VALIDITY_INVALID);
		assertCode(catchThrowable(() -> FreeCardService.requireValidity(today, today.minusDays(1), today)),
				HttpStatus.UNPROCESSABLE_CONTENT, FreeCardException.VALIDITY_INVALID);
		assertCode(catchThrowable(() -> FreeCardService.requireValidity(today.minusDays(5), today.minusDays(1),
				today)), HttpStatus.UNPROCESSABLE_CONTENT, FreeCardException.VALIDITY_INVALID);
		assertCode(catchThrowable(() -> FreeCardService.requireValidity(LocalDate.of(2026, 1, 31),
				LocalDate.of(2028, 1, 1), LocalDate.of(2026, 1, 1))), HttpStatus.UNPROCESSABLE_CONTENT,
				FreeCardException.VALIDITY_INVALID);
		// Exactly 24 covered months and a card that started in the past but is still running are fine.
		FreeCardService.requireValidity(LocalDate.of(2026, 1, 31), LocalDate.of(2027, 12, 1),
				LocalDate.of(2026, 1, 1));
		FreeCardService.requireValidity(today.withDayOfMonth(1), today, today);
	}

	@Test
	void cardsCannotStartBeforeTheCurrentMonth() {
		// R1: no back-dated card may unlock past months.
		LocalDate day = LocalDate.of(2026, 10, 21);
		Throwable thrown = catchThrowable(
				() -> FreeCardService.requireValidity(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 11, 30), day));
		assertCode(thrown, HttpStatus.UNPROCESSABLE_CONTENT, FreeCardException.VALIDITY_INVALID);
		assertThat(thrown).hasMessage("Free cards cannot start before the current month");
		FreeCardService.requireValidity(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), day);
	}

	@Test
	void nonMonthlyCourseIsRefusedAndDraftCourseIs404() {
		course("ONE_TIME", true);
		assertCode(catchThrowable(() -> service.issue(command("FULL", today, today))), HttpStatus.CONFLICT,
				FreeCardException.FREE_CARD_UNSUPPORTED_COURSE);

		when(courseLookupApi.findCatalogInfo(COURSE)).thenReturn(Optional.of(new CourseCatalogInfo(COURSE, "Draft",
				UUID.randomUUID(), "MONTHLY", "DRAFT", false, true, null, null, null, null, "LKR")));
		assertThatThrownBy(() -> service.issue(command("FULL", today, today))).isInstanceOf(NotFoundException.class);
	}

	@Test
	void overlappingUnrevokedCardIs409() {
		course("MONTHLY", true);
		FreeCard existing = FreeCard.issue(TENANT, STUDENT, COURSE, FreeCardType.FULL, today.minusDays(3),
				today.plusDays(3), ZoneId.of("UTC"), "x", "LKR", null, ADMIN, Instant.now(), UUID.randomUUID(), false);
		when(freeCardRepository.findUnrevokedByStudentIdAndCourseId(STUDENT, COURSE)).thenReturn(List.of(existing));

		assertCode(catchThrowable(() -> service.issue(command("FULL", today.plusDays(3), today.plusDays(10)))),
				HttpStatus.CONFLICT, FreeCardException.FREE_CARD_OVERLAP);
		verify(freeCardRepository, never()).save(any());
	}

	@Test
	void foreignStudentProfileIs404() {
		UUID foreign = UUID.randomUUID();
		when(studentLookupApi.resolveUserId(foreign)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.issue(new IssueCommand(foreign, COURSE, "FULL", today, today, "x",
				UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
		verifyNoInteractions(freeCardRepository);
	}

	@Test
	void replayWithTheSameKeyReturnsTheSameCardAndAnotherStudentIs409() {
		UUID key = UUID.randomUUID();
		FreeCard existing = FreeCard.issue(TENANT, STUDENT, COURSE, FreeCardType.FULL, today, today,
				ZoneId.of("UTC"), "x", "LKR", null, ADMIN, Instant.now(), key, false);
		ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
		when(freeCardRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

		FreeCardView replay = service.issue(new IssueCommand(PROFILE, COURSE, "FULL", today, today, "x", key));
		assertThat(replay.replay()).isTrue();
		assertThat(replay.id()).isEqualTo(existing.getId());

		UUID otherProfile = UUID.randomUUID();
		when(studentLookupApi.resolveUserId(otherProfile)).thenReturn(Optional.of(UUID.randomUUID()));
		assertCode(catchThrowable(() -> service.issue(new IssueCommand(otherProfile, COURSE, "FULL", today, today,
				"x", key))), HttpStatus.CONFLICT, FreeCardException.IDEMPOTENCY_KEY_REUSED);
		verify(freeCardRepository, never()).save(any());
	}

	@Test
	void revokeOnceWithReasonThenAlreadyRevoked() {
		FreeCard card = FreeCard.issue(TENANT, STUDENT, COURSE, FreeCardType.FULL, today, today.plusDays(5),
				ZoneId.of("UTC"), "x", "LKR", null, ADMIN, Instant.now(), UUID.randomUUID(), false);
		UUID id = UUID.randomUUID();
		ReflectionTestUtils.setField(card, "id", id);
		when(freeCardRepository.findById(id)).thenReturn(Optional.of(card));

		assertCode(catchThrowable(() -> service.revoke(id, " ")), HttpStatus.UNPROCESSABLE_CONTENT,
				FreeCardException.VALIDATION_ERROR);
		FreeCardView revoked = service.revoke(id, "left the class");
		assertThat(revoked.status()).isEqualTo("REVOKED");
		assertThat(revoked.revokedReason()).isEqualTo("left the class");
		assertCode(catchThrowable(() -> service.revoke(id, "again")), HttpStatus.CONFLICT,
				FreeCardException.FREE_CARD_ALREADY_REVOKED);

		ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
		verify(auditLogApi).record(audit.capture());
		assertThat(audit.getValue().action()).isEqualTo("free_card.revoked");
		assertThat(audit.getValue().metadata()).containsEntry("statusBefore", "ACTIVE")
			.containsEntry("statusAfter", "REVOKED");
	}

	@Test
	void unknownOrForeignCardIs404OnRevoke() {
		UUID id = UUID.randomUUID();
		when(freeCardRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.revoke(id, "reason")).isInstanceOf(NotFoundException.class);
	}

	@Test
	void financeStaffAndSupportMayViewButNeverIssueOrRevoke() {
		for (String role : List.of("FINANCE_STAFF", "STUDENT_SUPPORT", "READ_ONLY_AUDITOR", "STUDENT")) {
			as(role);
			assertThatThrownBy(() -> service.issue(command("FULL", today, today))).as(role)
				.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.revoke(UUID.randomUUID(), "x")).as(role)
				.isInstanceOf(AccessDeniedException.class);
		}
		as("STUDENT");
		assertThatThrownBy(() -> service.list(null, null, null, 0, 20)).isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(freeCardRepository, participationRepository);
		verify(studentLookupApi, never()).getStudentSummariesByUserId(anyCollection());
	}

}
