package com.lms.enrollmentmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.PageResponse;
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
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-030 Free Cards (F1-F9): issue, revoke and list complimentary passes.
 *
 * <p>Security: {@code FREE_CARDS} VIEW / CREATE_EDIT (issue) / DELETE
 * (revoke - a revocation stamp, never a row delete); tenant from the resolved
 * context (every repository read is structurally tenant-scoped); the student
 * is a student_profile id resolved server-side (404 otherwise); the actor is
 * the authenticated staff member. A card is never a payment: no order,
 * payment, ledger, settlement or postage row is ever written here (F7), and
 * paid entitlements are never touched (F5).
 */
@Service
public class FreeCardService {

	static final String AUDIT_ISSUED = "free_card.issued";

	static final String AUDIT_REVOKED = "free_card.revoked";

	static final int MAX_REASON = 1000;

	static final int MAX_PAGE_SIZE = 100;

	private final FreeCardRepository freeCardRepository;

	private final CourseParticipationRepository participationRepository;

	private final LessonAccessService lessonAccessService;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public FreeCardService(FreeCardRepository freeCardRepository, CourseParticipationRepository participationRepository,
			LessonAccessService lessonAccessService, CourseLookupApi courseLookupApi,
			StudentLookupApi studentLookupApi, UserProvisioningApi userProvisioningApi,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantConfigApi tenantConfigApi,
			TenantContext tenantContext) {
		this.freeCardRepository = freeCardRepository;
		this.participationRepository = participationRepository;
		this.lessonAccessService = lessonAccessService;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------
	// Issue.
	// ------------------------------------------------------------------

	@Transactional
	public FreeCardView issue(IssueCommand command) {
		permissionCheckService.requirePermission(DomainArea.FREE_CARDS, PermissionAction.CREATE_EDIT);
		if (command.idempotencyKey() == null) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "idempotencyKey is required");
		}
		if (command.courseId() == null) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "courseId is required");
		}
		FreeCardType type = parseType(command.cardType());
		String reason = requireReason(command.reason());
		UUID studentUserId = requireStudentUserId(command.studentId());
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		freeCardRepository.acquireLock("freecard-key:" + tenantId + ":" + command.idempotencyKey());
		Optional<FreeCard> existing = freeCardRepository.findByIdempotencyKey(command.idempotencyKey());
		if (existing.isPresent()) {
			FreeCard card = existing.get();
			if (!card.getStudentId().equals(studentUserId) || !card.getCourseId().equals(command.courseId())) {
				throw FreeCardException.conflict(FreeCardException.IDEMPOTENCY_KEY_REUSED,
						"This form was already used for a different Free Card - reopen it and try again");
			}
			return toViews(List.of(card), true).get(0);
		}

		ZoneId zone = tenantZone();
		LocalDate today = LocalDate.now(zone);
		requireValidity(command.validFrom(), command.validTo(), today);

		CourseCatalogInfo course = courseLookupApi.findCatalogInfo(command.courseId())
			.filter(info -> !"DRAFT".equals(info.status()))
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!"MONTHLY".equals(course.pricingModel())) {
			throw FreeCardException.conflict(FreeCardException.FREE_CARD_UNSUPPORTED_COURSE,
					"Free Cards can only be issued for monthly-fee courses");
		}

		// F5: serialize per (tenant, student, course), then check overlap.
		freeCardRepository.acquireLock("freecard:" + tenantId + ":" + studentUserId + ":" + command.courseId());
		boolean overlaps = freeCardRepository.findUnrevokedByStudentIdAndCourseId(studentUserId, command.courseId())
			.stream()
			.anyMatch(card -> card.overlaps(command.validFrom(), command.validTo()));
		if (overlaps) {
			throw FreeCardException.conflict(FreeCardException.FREE_CARD_OVERLAP,
					"This student already has a Free Card for this course in these dates");
		}

		Instant now = Instant.now();
		boolean enrollmentCreated = ensureParticipation(actorId, studentUserId, course, now);

		String currency = course.monthlyCurrency() != null ? course.monthlyCurrency() : course.currency();
		FreeCard card = freeCardRepository.save(FreeCard.issue(tenantId, studentUserId, course.courseId(), type,
				command.validFrom(), command.validTo(), zone, reason, currency, course.monthlyAmount(), actorId, now,
				command.idempotencyKey(), enrollmentCreated));
		auditLogApi.record(new AuditLogEntry(actorId, AUDIT_ISSUED, "free_card", card.getId(), card.getReason(),
				metadata("studentId", studentUserId, "courseId", card.getCourseId(), "cardType",
						card.getCardType().name(), "validFrom", card.getValidFrom().toString(), "validTo",
						card.getValidTo().toString(), "timezone", card.getTimezone(), "coveredMonths",
						months(card), "nominalMonthlyAmount", plain(card.getNominalMonthlyAmount()), "nominalValue",
						plain(card.getNominalValue()), "currency", card.getCurrency(), "enrollmentCreated",
						enrollmentCreated, "idempotencyKey", card.getIdempotencyKey())));
		return toViews(List.of(card), false).get(0);
	}

	/**
	 * F6: an existing participant (direct, via a linked parent, or a current
	 * legacy enrollment) needs nothing; otherwise the standard {@code
	 * STAFF_GRANT} participation is inserted when the course is available and
	 * open for registration (404 / 409 {@code REGISTRATION_CLOSED} otherwise),
	 * audited like a self-enrollment but with the staff actor.
	 * @return whether a participation row was created now.
	 */
	private boolean ensureParticipation(UUID actorId, UUID studentUserId, CourseCatalogInfo course, Instant now) {
		if (lessonAccessService.isParticipantOrLegacyEnrolled(studentUserId, course.courseId())) {
			return false;
		}
		if (!course.available()) {
			throw new NotFoundException("Course not found");
		}
		if (!course.registrationEnabled()) {
			throw new RegistrationClosedException();
		}
		int inserted = participationRepository.insertIfAbsent(studentUserId, course.courseId(),
				CourseParticipationSource.STAFF_GRANT, now);
		if (inserted != 1) {
			return false;
		}
		CourseParticipation participation = participationRepository
			.findByStudentIdAndCourseId(studentUserId, course.courseId())
			.orElseThrow(() -> new IllegalStateException("Participation row missing after upsert"));
		auditLogApi.record(new AuditLogEntry(actorId, CourseParticipationService.AUDIT_ACTION, "course_participation",
				participation.getId(), null, metadata("courseId", course.courseId(), "studentId", studentUserId,
						"source", CourseParticipationSource.STAFF_GRANT.name(), "via", "free_card")));
		return true;
	}

	static void requireValidity(LocalDate from, LocalDate to, LocalDate today) {
		if (from == null || to == null) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDITY_INVALID,
					"A Free Card needs a start and an end date");
		}
		if (to.isBefore(from)) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDITY_INVALID,
					"The end date cannot be before the start date");
		}
		if (from.isBefore(today.withDayOfMonth(1))) {
			// R1: no back-dated card may unlock past months.
			throw FreeCardException.unprocessable(FreeCardException.VALIDITY_INVALID,
					"Free cards cannot start before the current month");
		}
		if (to.isBefore(today)) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDITY_INVALID,
					"The end date cannot be in the past");
		}
		if (FreeCard.coveredMonthCount(from, to) > FreeCard.MAX_COVERED_MONTHS) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDITY_INVALID,
					"A Free Card can cover at most " + FreeCard.MAX_COVERED_MONTHS + " months");
		}
	}

	static FreeCardType parseType(String value) {
		if (value == null || value.isBlank()) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "cardType is required");
		}
		String normalized = value.strip().toUpperCase(Locale.ROOT);
		if ("FULL".equals(normalized)) {
			return FreeCardType.FULL;
		}
		if ("HALF".equals(normalized)) {
			throw FreeCardException.unprocessable(FreeCardException.FREE_CARD_TYPE_UNSUPPORTED,
					"Half Free Cards are not supported yet - issue a Full card");
		}
		throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "Unknown card type");
	}

	private static String requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "A reason is required");
		}
		String stripped = reason.strip();
		if (stripped.length() > MAX_REASON) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR,
					"The reason is at most " + MAX_REASON + " characters");
		}
		return stripped;
	}

	// ------------------------------------------------------------------
	// Revoke.
	// ------------------------------------------------------------------

	/** F8: reason required; once only (409 FREE_CARD_ALREADY_REVOKED); 404 for unknown / other-tenant ids. */
	@Transactional
	public FreeCardView revoke(UUID cardId, String reason) {
		permissionCheckService.requirePermission(DomainArea.FREE_CARDS, PermissionAction.DELETE);
		String why = requireReason(reason);
		UUID tenantId = tenantContext.getTenantId();
		freeCardRepository.acquireLock("freecard-revoke:" + tenantId + ":" + cardId);
		FreeCard card = freeCardRepository.findById(cardId)
			.orElseThrow(() -> new NotFoundException("Free Card not found"));
		if (card.isRevoked()) {
			throw FreeCardException.conflict(FreeCardException.FREE_CARD_ALREADY_REVOKED,
					"This Free Card was already revoked");
		}
		Instant now = Instant.now();
		String before = card.statusAt(now).name();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		card.revoke(actorId, now, why);
		card = freeCardRepository.save(card);
		auditLogApi.record(new AuditLogEntry(actorId, AUDIT_REVOKED, "free_card", card.getId(), why,
				metadata("studentId", card.getStudentId(), "courseId", card.getCourseId(), "statusBefore", before,
						"statusAfter", FreeCard.Status.REVOKED.name(), "coveredMonths", months(card), "validFrom",
						card.getValidFrom().toString(), "validTo", card.getValidTo().toString(), "timezone",
						card.getTimezone())));
		return toViews(List.of(card), false).get(0);
	}

	// ------------------------------------------------------------------
	// List.
	// ------------------------------------------------------------------

	/** Newest first; {@code studentId} is a student_profile id (404 when it does not resolve in this tenant). */
	@Transactional(readOnly = true)
	public PageResponse<FreeCardView> list(UUID studentProfileId, UUID courseId, String status, int page, int size) {
		permissionCheckService.requirePermission(DomainArea.FREE_CARDS, PermissionAction.VIEW);
		UUID studentUserId = studentProfileId == null ? null : requireStudentUserId(studentProfileId);
		FreeCard.Status parsedStatus = parseStatus(status);
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		Page<FreeCard> cards = freeCardRepository.findPage(studentUserId, courseId, parsedStatus, Instant.now(),
				PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "issuedAt").and(Sort.by("id"))));
		return new PageResponse<>(toViews(cards.getContent(), false), cards.getNumber(), cards.getSize(),
				cards.getTotalElements(), cards.getTotalPages());
	}

	private static FreeCard.Status parseStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		try {
			return FreeCard.Status.valueOf(status.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "Unknown status");
		}
	}

	// ------------------------------------------------------------------
	// Mapping and helpers.
	// ------------------------------------------------------------------

	private List<FreeCardView> toViews(List<FreeCard> cards, boolean replay) {
		if (cards.isEmpty()) {
			return List.of();
		}
		Set<UUID> studentIds = new LinkedHashSet<>();
		Set<UUID> courseIds = new LinkedHashSet<>();
		Set<UUID> staffIds = new LinkedHashSet<>();
		for (FreeCard card : cards) {
			studentIds.add(card.getStudentId());
			courseIds.add(card.getCourseId());
			staffIds.add(card.getIssuedBy());
			if (card.getRevokedBy() != null) {
				staffIds.add(card.getRevokedBy());
			}
		}
		Map<UUID, StudentSummary> students = new HashMap<>();
		studentLookupApi.getStudentSummariesByUserId(studentIds).forEach(s -> students.put(s.userId(), s));
		Map<UUID, String> titles = new HashMap<>();
		courseLookupApi.getCourseSummaries(courseIds).forEach(c -> titles.put(c.id(), c.name()));
		Map<UUID, String> staff = new HashMap<>();
		for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(staffIds)) {
			staff.put(summary.userId(), summary.email());
		}
		Instant now = Instant.now();
		return cards.stream().map(card -> {
			StudentSummary student = students.get(card.getStudentId());
			return new FreeCardView(card.getId(), student == null ? null : student.studentProfileId(),
					student == null ? null : student.name(), card.getCourseId(), titles.get(card.getCourseId()),
					card.getCardType().name(), card.getValidFrom(), card.getValidTo(), card.getTimezone(),
					card.getEffectiveFrom(), card.getEffectiveUntil(), months(card), card.statusAt(now).name(),
					card.getReason(), card.getCurrency(), card.getNominalMonthlyAmount(), card.getNominalValue(),
					card.getIssuedBy(), staff.get(card.getIssuedBy()), card.getIssuedAt(), card.getRevokedAt(),
					card.getRevokedBy(), card.getRevokedBy() == null ? null : staff.get(card.getRevokedBy()),
					card.getRevokedReason(), card.isEnrollmentCreated(), replay);
		}).toList();
	}

	private static List<String> months(FreeCard card) {
		return card.coveredMonths().stream().map(YearMonth::toString).toList();
	}

	private UUID requireStudentUserId(UUID studentProfileId) {
		if (studentProfileId == null) {
			throw FreeCardException.unprocessable(FreeCardException.VALIDATION_ERROR, "studentId is required");
		}
		return studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
	}

	private ZoneId tenantZone() {
		try {
			Object configured = tenantConfigApi
				.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zone && !zone.isBlank()) {
				return ZoneId.of(zone);
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// fall through to UTC
		}
		return ZoneId.of("UTC");
	}

	private static String plain(java.math.BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

	/** Key/value pairs with {@code null} values dropped (AuditLogEntry copies via {@code Map.copyOf}). */
	private static Map<String, Object> metadata(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			Object value = keyValues[i + 1];
			if (value != null) {
				result.put((String) keyValues[i], value instanceof UUID ? value.toString() : value);
			}
		}
		return result;
	}

}
