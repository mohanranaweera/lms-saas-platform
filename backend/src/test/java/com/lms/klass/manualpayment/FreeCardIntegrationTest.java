package com.lms.klass.manualpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * ADR-030 Free Cards (plan §3 F1-F9, §4), PostgreSQL via Testcontainers +
 * MockMvc with the real security filter chain. Acceptance IDs (brief §11):
 * B, G, H, I, J, K, L, M, N, O, P plus F6 participation, unsupported course
 * and HALF.
 */
class FreeCardIntegrationTest extends KlassManualPaymentTestSupport {

	// ------------------------------------------------------------------
	// G + M + P: issue covers its month only; revoke removes it; audit.
	// ------------------------------------------------------------------

	@Test
	void issuedCardCoversOnlyItsMonthsCoveredLessonsAndRevocationRemovesAccessKeepingTheRow() {
		Fx fx = seed("fc-g");
		UUID tenantId = fx.tenantId();
		Map<String, Long> before = financialCounts(tenantId);
		assertThat(access(fx, fx.curLesson())).isEqualTo("PAYMENT_REQUIRED");

		JsonNode card = issueMonthCardOrFail(fx, CUR);
		UUID cardId = UUID.fromString(card.path("id").asString());
		assertThat(card.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(card.path("cardType").asString()).isEqualTo("FULL");
		assertThat(card.path("studentId").asString()).isEqualTo(fx.studentProfileId().toString());
		assertThat(card.path("coveredMonths")).hasSize(1);
		assertThat(card.path("coveredMonths").get(0).asString()).isEqualTo(CUR.toString());
		assertThat(card.path("nominalMonthlyAmount").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		assertThat(card.path("nominalValue").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		assertThat(card.path("enrollmentCreated").asBoolean()).isFalse();
		assertThat(card.path("issuedBy").asString()).isEqualTo(fx.admin().getId().toString());

		// F7: no order / payment / ledger / charge / entitlement rows.
		assertThat(financialCounts(tenantId)).isEqualTo(before);

		// G + M: covered month ALLOWED in tree and launch; PAID and other months still need payment.
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
		assertThat(tree(fx.host(), fx.studentToken(), fx.course()).get(fx.curLesson().toString())
			.path("access").path("payment").asString()).isEqualTo("COVERED");
		assertThat(access(fx, fx.paidLesson())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(access(fx, fx.prevLesson())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(access(fx, fx.nextLesson())).isEqualTo("PAYMENT_REQUIRED");

		// Revoke: reason required, then REVOKED, access gone (tree and launch agree).
		MvcResult noReason = revokeCard(fx.host(), fx.adminToken(), cardId, " ");
		assertThat(status(noReason)).isEqualTo(422);
		MvcResult revoked = revokeCard(fx.host(), fx.adminToken(), cardId, "Issued by mistake");
		assertThat(status(revoked)).as(rawContent(revoked)).isEqualTo(200);
		assertThat(data(revoked).path("status").asString()).isEqualTo("REVOKED");
		assertThat(data(revoked).path("revokedReason").asString()).isEqualTo("Issued by mistake");
		assertThat(access(fx, fx.curLesson())).isEqualTo("PAYMENT_REQUIRED");
		MvcResult twice = revokeCard(fx.host(), fx.adminToken(), cardId, "Again");
		assertThat(status(twice)).isEqualTo(409);
		assertThat(errorCode(twice)).isEqualTo("FREE_CARD_ALREADY_REVOKED");

		// P: the original row stays with revoked fields; audit rows for issue + revoke.
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM free_card WHERE id = ?", cardId);
		assertThat(row.get("revoked_at")).isNotNull();
		assertThat(row.get("revoked_by")).isEqualTo(fx.admin().getId());
		assertThat(row.get("revoked_reason")).isEqualTo("Issued by mistake");
		assertThat(row.get("reason")).isEqualTo("Scholarship - needy student");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'free_card.issued' "
				+ "AND target_id = ? AND actor_id = ?", tenantId, cardId, fx.admin().getId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'free_card.revoked' "
				+ "AND target_id = ? AND reason = 'Issued by mistake'", tenantId, cardId)).isEqualTo(1);
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM free_card WHERE id = ?", cardId))
			.isInstanceOf(DataAccessException.class);
		assertThat(financialCounts(tenantId)).isEqualTo(before);
	}

	// ------------------------------------------------------------------
	// H: scheduled / expired / invalid validity.
	// ------------------------------------------------------------------

	@Test
	void futureCardIsScheduledWithoutAccessExpiredCardGrantsNothingAndInvalidValidityIs422() {
		Fx fx = seed("fc-h");
		JsonNode future = issueMonthCardOrFail(fx, NEXT);
		assertThat(future.path("status").asString()).isEqualTo("SCHEDULED");
		assertThat(access(fx, fx.nextLesson())).isEqualTo("PAYMENT_REQUIRED");

		// Validity rules (F3 + fix-pass rule d).
		LocalDate today = today();
		List<LocalDate[]> invalid = List.of(new LocalDate[] { null, today },
				new LocalDate[] { CUR.atDay(1), null },
				new LocalDate[] { today.plusDays(3), today.plusDays(1) },
				new LocalDate[] { PREV.atDay(1), PREV.atEndOfMonth() },
				new LocalDate[] { PREV.atDay(1), today },
				new LocalDate[] { CUR.atDay(1), CUR.plusMonths(30).atDay(1) });
		for (LocalDate[] range : invalid) {
			MvcResult result = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), fx.course(), "FULL",
					range[0], range[1], UUID.randomUUID());
			assertThat(status(result)).as(range[0] + ".." + range[1] + " " + rawContent(result)).isEqualTo(422);
			assertThat(errorCode(result)).isEqualTo("VALIDITY_INVALID");
		}

		// Expired card (inserted directly - the API refuses past windows): covers PREV but its window ended.
		UUID expiredId = UUID.randomUUID();
		Instant from = PREV.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant until = CUR.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		jdbcTemplate.update("INSERT INTO free_card (id, tenant_id, student_id, course_id, card_type, valid_from, "
				+ "valid_to, timezone, effective_from, effective_until, reason, currency, nominal_monthly_amount, "
				+ "nominal_value, issued_by, issued_at, idempotency_key, enrollment_created) VALUES (?, ?, ?, ?, "
				+ "'FULL', ?, ?, 'UTC', ?, ?, 'Old card', 'LKR', 3000.00, 3000.00, ?, ?, ?, false)", expiredId,
				fx.tenantId(), fx.student().getId(), fx.course(), java.sql.Date.valueOf(PREV.atDay(1)),
				java.sql.Date.valueOf(PREV.atEndOfMonth()), java.sql.Timestamp.from(from),
				java.sql.Timestamp.from(until), fx.admin().getId(), java.sql.Timestamp.from(from), UUID.randomUUID());
		assertThat(access(fx, fx.prevLesson())).isEqualTo("PAYMENT_REQUIRED");
		MvcResult expired = listCards(fx.host(), fx.adminToken(), "status=EXPIRED");
		assertThat(status(expired)).as(rawContent(expired)).isEqualTo(200);
		List<String> ids = new ArrayList<>();
		data(expired).path("content").forEach(c -> ids.add(c.path("id").asString()));
		assertThat(ids).containsExactly(expiredId.toString());
		List<String> scheduled = new ArrayList<>();
		data(listCards(fx.host(), fx.adminToken(), "status=SCHEDULED")).path("content")
			.forEach(c -> scheduled.add(c.path("id").asString()));
		assertThat(scheduled).containsExactly(future.path("id").asString());
		// An expired, non-revoked card does not block a card for the current month.
		issueMonthCardOrFail(fx, CUR);
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
	}

	// ------------------------------------------------------------------
	// I: overlap and idempotency.
	// ------------------------------------------------------------------

	@Test
	void overlappingCardIs409AndSameKeyReplaysTheSameCard() {
		Fx fx = seed("fc-i");
		UUID key = UUID.randomUUID();
		MvcResult first = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), fx.course(), "FULL",
				CUR.atDay(1), CUR.atEndOfMonth(), key);
		assertThat(status(first)).as(rawContent(first)).isEqualTo(201);
		String cardId = data(first).path("id").asString();

		MvcResult replay = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), fx.course(), "FULL",
				CUR.atDay(1), CUR.atEndOfMonth(), key);
		assertThat(status(replay)).as(rawContent(replay)).isEqualTo(200);
		assertThat(data(replay).path("id").asString()).isEqualTo(cardId);
		assertThat(data(replay).path("replay").asBoolean()).isTrue();

		MvcResult overlap = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), fx.course(), "FULL",
				CUR.atEndOfMonth(), NEXT.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(overlap)).as(rawContent(overlap)).isEqualTo(409);
		assertThat(errorCode(overlap)).isEqualTo("FREE_CARD_OVERLAP");

		// Same key for another course -> IDEMPOTENCY_KEY_REUSED.
		UUID other = createCourse(fx.host(), fx.adminToken(), fx.teacher().getId(), "Other fc-i");
		MvcResult reused = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), other, "FULL", CUR.atDay(1),
				CUR.atEndOfMonth(), key);
		assertThat(status(reused)).isEqualTo(409);
		assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ?", fx.tenantId())).isEqualTo(1);

		// Non-overlapping next month is fine; after revoking, the same dates can be reissued.
		issueMonthCardOrFail(fx, NEXT);
		assertThat(status(revokeCard(fx.host(), fx.adminToken(), UUID.fromString(cardId), "Replace"))).isEqualTo(200);
		issueMonthCardOrFail(fx, CUR);
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ?", fx.tenantId())).isEqualTo(3);
	}

	// ------------------------------------------------------------------
	// J: paid + covered; revoking the card keeps the paid access.
	// ------------------------------------------------------------------

	@Test
	void revokingACardForAMonthAlsoPaidManuallyKeepsThePaidAccess() {
		Fx fx = seed("fc-j");
		JsonNode receipt = recordOrFail(fx, fx.financeToken(), List.of(monthItem(fx.course(), CUR)));
		UUID entitlement = jdbcTemplate.queryForObject("SELECT id FROM content_entitlement WHERE source_payment_id = ?",
				UUID.class, UUID.fromString(receipt.path("paymentId").asString()));
		String entitlementBefore = rowSnapshot("content_entitlement", entitlement);
		JsonNode card = issueMonthCardOrFail(fx, CUR);
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");

		assertThat(status(revokeCard(fx.host(), fx.adminToken(), UUID.fromString(card.path("id").asString()),
				"No longer needed"))).isEqualTo(200);
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
		assertThat(rowSnapshot("content_entitlement", entitlement)).isEqualTo(entitlementBefore);
	}

	// ------------------------------------------------------------------
	// K + L: roles.
	// ------------------------------------------------------------------

	@Test
	void studentTeacherAndFinanceCannotIssueOrRevokeButFinanceMayView() {
		Fx fx = seed("fc-kl");
		JsonNode card = issueMonthCardOrFail(fx, CUR);
		UUID cardId = UUID.fromString(card.path("id").asString());
		for (String token : List.of(fx.studentToken(), fx.teacherToken(), fx.financeToken())) {
			assertThat(status(issueCard(fx.host(), token, fx.studentProfileId(), fx.course(), "FULL", NEXT.atDay(1),
					NEXT.atEndOfMonth(), UUID.randomUUID()))).isEqualTo(403);
			assertThat(status(revokeCard(fx.host(), token, cardId, "Not allowed"))).isEqualTo(403);
		}
		assertThat(status(listCards(fx.host(), fx.studentToken(), null))).isEqualTo(403);
		MvcResult financeList = listCards(fx.host(), fx.financeToken(), null);
		assertThat(status(financeList)).as(rawContent(financeList)).isEqualTo(200);
		assertThat(data(financeList).path("content")).hasSize(1);
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ?", fx.tenantId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM free_card WHERE id = ? AND revoked_at IS NULL", cardId)).isEqualTo(1);
		// L: the same Finance Staff can still record a receipt.
		recordOrFail(fx, fx.financeToken(), List.of(monthItem(fx.course(), NEXT)));
	}

	// ------------------------------------------------------------------
	// B: cross-tenant.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void crossTenantStudentCourseAndCardIdsAre404AndListsNeverShowTheOtherTenant() {
		Fx a = seed("fc-b-a");
		Fx b = seed("fc-b-b");
		JsonNode aCard = issueMonthCardOrFail(a, CUR);
		UUID aCardId = UUID.fromString(aCard.path("id").asString());
		long bCards = count("SELECT count(*) FROM free_card WHERE tenant_id = ?", b.tenantId());

		// B admin: A's student profile; B's student + A's course; A's card id.
		assertThat(status(issueCard(b.host(), b.adminToken(), a.studentProfileId(), b.course(), "FULL", NEXT.atDay(1),
				NEXT.atEndOfMonth(), UUID.randomUUID()))).isEqualTo(404);
		assertThat(status(issueCard(b.host(), b.adminToken(), b.studentProfileId(), a.course(), "FULL", NEXT.atDay(1),
				NEXT.atEndOfMonth(), UUID.randomUUID()))).isEqualTo(404);
		assertThat(status(revokeCard(b.host(), b.adminToken(), aCardId, "Hostile"))).isEqualTo(404);
		assertThat(status(listCards(b.host(), b.adminToken(), "studentId=" + a.studentProfileId()))).isEqualTo(404);
		// Unfiltered and course-filtered lists in B never contain A's rows.
		MvcResult bList = listCards(b.host(), b.adminToken(), null);
		assertThat(status(bList)).isEqualTo(200);
		assertThat(data(bList).path("content")).isEmpty();
		assertThat(rawContent(bList)).doesNotContain(aCardId.toString());
		MvcResult bByCourse = listCards(b.host(), b.adminToken(), "courseId=" + a.course());
		assertThat(status(bByCourse)).isIn(200, 404);
		assertThat(rawContent(bByCourse)).doesNotContain(aCardId.toString());
		// Idempotency key of A's card replayed in B does not return A's card.
		MvcResult keyProbe = issueCard(b.host(), b.adminToken(), b.studentProfileId(), b.course(), "FULL",
				CUR.atDay(1), CUR.atEndOfMonth(), UUID.fromString(jdbcTemplate.queryForObject(
						"SELECT idempotency_key::text FROM free_card WHERE id = ?", String.class, aCardId)));
		assertThat(rawContent(keyProbe)).doesNotContain(aCardId.toString());
		// B's student has no access to A's course through anything.
		assertThat(status(launch(b.host(), b.studentToken(), a.course(), a.curLesson()))).isEqualTo(404);

		assertThat(count("SELECT count(*) FROM free_card WHERE id = ? AND revoked_at IS NULL", aCardId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ? AND student_id = ?", b.tenantId(),
				a.student().getId())).isZero();
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ? AND course_id = ?", b.tenantId(),
				a.course())).isZero();
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ?", b.tenantId()))
			.isLessThanOrEqualTo(bCards + 1);
		assertThat(access(a, a.curLesson())).isEqualTo("ALLOWED");
	}

	// ------------------------------------------------------------------
	// N: linked course.
	// ------------------------------------------------------------------

	@Test
	void cardOnALinkedParentCourseDoesNotCoverSourceCourseLessons() {
		Fx fx = seed("fc-n");
		UUID parent = createCourse(fx.host(), fx.adminToken(), fx.teacher().getId(), "Parent fc-n",
				"linkedCourseIds", List.of(fx.course()));
		UUID parentModule = createModuleOrFail(fx.host(), fx.adminToken(), parent, "Parent module", 1).id();
		UUID parentCur = coveredLesson(fx.host(), fx.adminToken(), parent, parentModule, "Parent class", 1, CUR);
		ExtraStudent s = extraStudent(fx, "parent-only@example.test");
		assertThat(status(selfEnroll(fx.host(), s.token(), parent))).isEqualTo(200);

		MvcResult issued = issueCard(fx.host(), fx.adminToken(), s.profileId(), parent, "FULL", CUR.atDay(1),
				CUR.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(issued)).as(rawContent(issued)).isEqualTo(201);

		Map<String, JsonNode> tree = tree(fx.host(), s.token(), parent);
		assertThat(tree.get(parentCur.toString()).path("access").path("status").asString()).isEqualTo("ALLOWED");
		assertThat(tree.get(fx.curLesson().toString()).path("access").path("status").asString())
			.isEqualTo("PAYMENT_REQUIRED");
		assertThat(launchCode(fx.host(), s.token(), parent, parentCur)).isEqualTo("ALLOWED");
		assertThat(launchCode(fx.host(), s.token(), fx.course(), fx.curLesson())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ? "
				+ "AND course_id = ?", fx.tenantId(), s.user().getId(), fx.course())).isZero();
	}

	// ------------------------------------------------------------------
	// O: no postage / fee rows from a card.
	// ------------------------------------------------------------------

	@Test
	void cardForACourseWithFacilityFeeAndPostageCreatesNoChargeRows() {
		Fx fx = seed("fc-o");
		setFacilityFee(fx, "PER_LINE", 100);
		setPostage(fx, fx.course(), 200);
		Map<String, Long> before = financialCounts(fx.tenantId());
		JsonNode card = issueMonthCardOrFail(fx, CUR);
		assertThat(card.path("nominalValue").decimalValue()).isEqualByComparingTo(MONTHLY_PRICE);
		assertThat(financialCounts(fx.tenantId())).isEqualTo(before);
		assertThat(access(fx, fx.curLesson())).isEqualTo("ALLOWED");
	}

	// ------------------------------------------------------------------
	// F6 participation, unsupported course, HALF.
	// ------------------------------------------------------------------

	@Test
	void cardForANonParticipantCreatesStaffGrantParticipationAndUnsupportedInputsAreRefused() {
		Fx fx = seed("fc-f6");
		UUID tenantId = fx.tenantId();
		ExtraStudent s = extraStudent(fx, "newcomer@example.test");
		assertThat(launchCode(fx.host(), s.token(), fx.course(), fx.curLesson())).isEqualTo("NOT_ENROLLED");

		MvcResult issued = issueCard(fx.host(), fx.adminToken(), s.profileId(), fx.course(), "full", CUR.atDay(1),
				CUR.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(issued)).as(rawContent(issued)).isEqualTo(201);
		assertThat(data(issued).path("enrollmentCreated").asBoolean()).isTrue();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ? "
				+ "AND course_id = ? AND source = 'STAFF_GRANT'", tenantId, s.user().getId(), fx.course())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = "
				+ "'course.participation_created' AND actor_id = ?", tenantId, fx.admin().getId())).isEqualTo(1);
		assertThat(launchCode(fx.host(), s.token(), fx.course(), fx.curLesson())).isEqualTo("ALLOWED");

		// Registration closed + non-participant -> 409 REGISTRATION_CLOSED, nothing written.
		ExtraStudent late = extraStudent(fx, "late@example.test");
		assertThat(status(send(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}/registration", fx.course()),
				Map.of("enabled", false)))).isEqualTo(200);
		MvcResult closed = issueCard(fx.host(), fx.adminToken(), late.profileId(), fx.course(), "FULL", CUR.atDay(1),
				CUR.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(closed)).as(rawContent(closed)).isEqualTo(409);
		assertThat(errorCode(closed)).isEqualTo("REGISTRATION_CLOSED");
		assertThat(count("SELECT count(*) FROM course_participation WHERE student_id = ?", late.user().getId()))
			.isZero();
		assertThat(count("SELECT count(*) FROM free_card WHERE student_id = ?", late.user().getId())).isZero();

		// Non-MONTHLY course -> 409 FREE_CARD_UNSUPPORTED_COURSE.
		UUID free = createCourse(fx.host(), fx.adminToken(), fx.teacher().getId(), "Free fc-f6", "pricingModel",
				"FREE", "price", null);
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), free))).isEqualTo(200);
		MvcResult unsupported = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), free, "FULL",
				CUR.atDay(1), CUR.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(unsupported)).as(rawContent(unsupported)).isEqualTo(409);
		assertThat(errorCode(unsupported)).isEqualTo("FREE_CARD_UNSUPPORTED_COURSE");

		// HALF -> 422 FREE_CARD_TYPE_UNSUPPORTED.
		MvcResult half = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), fx.course(), "HALF",
				CUR.atDay(1), CUR.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(half)).isEqualTo(422);
		assertThat(errorCode(half)).isEqualTo("FREE_CARD_TYPE_UNSUPPORTED");
		assertThat(count("SELECT count(*) FROM free_card WHERE tenant_id = ?", tenantId)).isEqualTo(1);
		assertThat(financialCounts(tenantId).get("student_order")).isZero();
	}

}
