package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipDownloadUrlResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipNoteResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipPendingCountResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PAY-HUB-01 slip-side contract (plan §6.4-6.8): review-queue search and the
 * new student/order fields, the pending count, the download-url content type,
 * and append-only staff notes - each with its role boundary and an explicit
 * cross-tenant negative test.
 */
@Tag("cross-tenant")
class PaymentsHubSlipIntegrationTest extends SlipTestSupport {

	// ------------------------------------------------------------------
	// Review-queue search + enrichment (6.4)
	// ------------------------------------------------------------------

	@Test
	void reviewQueueRowsCarryStudentProfileNameMobileAndOrderNumber() {
		SlipFixture f = seedTenantWithOrder("hub-q-fields");
		UUID profileId = seedStudentProfile(f, "Kamala Perera", "0771234567");
		uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-FIELDS", pdfFile("slip.pdf"));

		PaymentSlipResponse row = singleRow(searchQueue(f.host(), f.adminToken(), null));

		assertThat(row.studentProfileId()).isEqualTo(profileId);
		assertThat(row.studentName()).isEqualTo("Kamala Perera");
		assertThat(row.studentMobile()).isEqualTo("0771234567");
		assertThat(row.orderNumber()).isEqualTo(f.order().orderNumber());
		assertThat(row.displayOrderNumber()).isEqualTo(f.order().displayOrderNumber());
	}

	@Test
	void searchMatchesStudentNameMobileEmailReferenceAndOrderNumber() {
		SlipFixture f = seedTenantWithOrder("hub-q-search");
		seedStudentProfile(f, "Nimal Silva", "0719998887");
		PaymentSlipResponse slip = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "BOC-77812",
				pdfFile("slip.pdf"));

		for (String term : List.of("nimal", "SILVA", "99988", "student@example", "boc-778",
				f.order().displayOrderNumber(), String.valueOf(f.order().orderNumber()))) {
			assertThat(rowIds(searchQueue(f.host(), f.adminToken(), term))).as(term).containsExactly(slip.id());
		}
		assertThat(rowIds(searchQueue(f.host(), f.adminToken(), "no-such-student"))).isEmpty();
		// LIKE wildcards in the term are literal, never "match everything".
		assertThat(rowIds(searchQueue(f.host(), f.adminToken(), "%"))).isEmpty();
	}

	@Test
	void searchStillDefaultsToPendingOnlyAndCombinesWithAStatusFilter() {
		SlipFixture f = seedTenantWithOrder("hub-q-status");
		PaymentSlipResponse slip = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-STATUS",
				pdfFile("slip.pdf"));
		assertThat(approveSlip(f.host(), f.financeToken(), slip.id(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(rowIds(searchQueue(f.host(), f.adminToken(), "REF-HUB-STATUS"))).isEmpty();
		HttpResult<PageResponse<PaymentSlipResponse>> approved = parsePage(perform(authenticated(
				get("/api/v1/payment-slips/review-queue").param("status", "APPROVED").param("search", "REF-HUB-STATUS"),
				f.host(), f.adminToken())), PaymentSlipResponse.class);
		assertThat(rowIds(approved)).containsExactly(slip.id());
	}

	@Test
	void searchLongerThan100CharactersIsRejectedWith400() {
		SlipFixture f = seedTenantWithOrder("hub-q-long");

		HttpResult<PageResponse<PaymentSlipResponse>> result = searchQueue(f.host(), f.adminToken(), "x".repeat(101));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void searchNeverReturnsAnotherTenantsSlipEvenWithTheSameReferenceOrOrderNumber() {
		SlipFixture tenantA = seedTenantWithOrder("hub-q-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("hub-q-xt-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"SHARED-REF-1", pdfFile("slip.pdf"));
		PaymentSlipResponse slipB = uploadSlipOrFail(tenantB.host(), tenantB.studentToken(), tenantB.order().id(),
				"SHARED-REF-1", pdfFile("slip-b.pdf", distinctPdfBytes("hub-q-xt-b")));

		assertThat(rowIds(searchQueue(tenantA.host(), tenantA.adminToken(), "SHARED-REF-1")))
			.containsExactly(slipA.id());
		assertThat(rowIds(searchQueue(tenantA.host(), tenantA.adminToken(), tenantB.order().displayOrderNumber())))
			.doesNotContain(slipB.id());
		assertThat(rowIds(searchQueue(tenantB.host(), tenantB.adminToken(), "student@example")))
			.containsExactly(slipB.id());
	}

	// ------------------------------------------------------------------
	// Pending count (6.5)
	// ------------------------------------------------------------------

	@Test
	void pendingCountIsTenantScopedAndStaffOnly() {
		SlipFixture tenantA = seedTenantWithOrder("hub-count-a");
		SlipFixture tenantB = seedTenantWithOrder("hub-count-b");
		uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(), "REF-HUB-COUNT",
				pdfFile("slip.pdf"));

		assertThat(pendingCount(tenantA.host(), tenantA.adminToken()).getBody().data().pending()).isEqualTo(1L);
		assertThat(pendingCount(tenantB.host(), tenantB.adminToken()).getBody().data().pending()).isZero();
		assertThat(pendingCount(tenantA.host(), tenantA.studentToken()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Download-url content type (6.6)
	// ------------------------------------------------------------------

	@Test
	void downloadUrlReportsTheSniffedContentType() {
		SlipFixture f = seedTenantWithOrder("hub-ctype");
		PaymentSlipResponse pdf = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-PDF",
				pdfFile("slip.pdf"));

		HttpResult<SlipDownloadUrlResponse> result = getSlipDownloadUrl(f.host(), f.adminToken(), pdf.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().contentType()).isEqualTo("application/pdf");
		assertThat(result.getBody().data().url()).isNotBlank();
	}

	// ------------------------------------------------------------------
	// Staff notes (6.7 / 6.8)
	// ------------------------------------------------------------------

	@Test
	void financeStaffAddsNotesThatListOldestFirstWithAuthorAndNeverChangeTheSlip() {
		SlipFixture f = seedTenantWithOrder("hub-note-ok");
		PaymentSlipResponse slip = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-NOTE",
				pdfFile("slip.pdf"));

		HttpResult<SlipNoteResponse> first = addNote(f.host(), f.financeToken(), slip.id(), "  Called the student  ");
		HttpResult<SlipNoteResponse> second = addNote(f.host(), f.adminToken(), slip.id(), "Amount matches");

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(first.getBody().data().body()).isEqualTo("Called the student");
		assertThat(first.getBody().data().authorEmail()).isEqualTo("finance@example.test");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		List<SlipNoteResponse> notes = listNotes(f.host(), f.adminToken(), slip.id()).getBody().data();
		assertThat(notes).extracting(SlipNoteResponse::body).containsExactly("Called the student", "Amount matches");
		assertThat(getSlip(f.host(), f.adminToken(), slip.id()).getBody().data().status())
			.isEqualTo(PaymentSlipStatus.UNDER_REVIEW);
	}

	@Test
	void blankOrOverlongNoteIsRejectedWith400AndWritesNothing() {
		SlipFixture f = seedTenantWithOrder("hub-note-invalid");
		PaymentSlipResponse slip = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-NOTE-BAD",
				pdfFile("slip.pdf"));

		assertThat(addNote(f.host(), f.adminToken(), slip.id(), "   ").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(addNote(f.host(), f.adminToken(), slip.id(), "x".repeat(1001)).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(noteRowCount(slip.id())).isZero();
	}

	@Test
	void readOnlyRolesMayReadButNotWriteNotesAndStudentsMayDoNeither() {
		SlipFixture f = seedTenantWithOrder("hub-note-roles");
		seedTenantUser(f.tenant().getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		seedTenantUser(f.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String supportToken = loginAndGetToken(f.host(), "support@example.test");
		String auditorToken = loginAndGetToken(f.host(), "auditor@example.test");
		PaymentSlipResponse slip = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-NOTE-ROLE",
				pdfFile("slip.pdf"));
		addNote(f.host(), f.adminToken(), slip.id(), "Internal remark");

		assertThat(addNote(f.host(), supportToken, slip.id(), "nope").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(addNote(f.host(), auditorToken, slip.id(), "nope").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listNotes(f.host(), auditorToken, slip.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		// The slip's own student never sees staff notes.
		assertThat(listNotes(f.host(), f.studentToken(), slip.id()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(addNote(f.host(), f.studentToken(), slip.id(), "hi").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rawContent(perform(authenticated(get("/api/v1/payment-slips/{slipId}", slip.id()), f.host(),
				f.studentToken())))).doesNotContain("Internal remark");
		assertThat(noteRowCount(slip.id())).isEqualTo(1L);
	}

	@Test
	void notesOnAnotherTenantsSlipAre404AndWriteNothing() {
		SlipFixture tenantA = seedTenantWithOrder("hub-note-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("hub-note-xt-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-HUB-NOTE-XT", pdfFile("slip.pdf"));
		addNote(tenantA.host(), tenantA.adminToken(), slipA.id(), "Tenant A only");

		assertThat(addNote(tenantB.host(), tenantB.adminToken(), slipA.id(), "cross").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<List<SlipNoteResponse>> read = listNotes(tenantB.host(), tenantB.adminToken(), slipA.id());
		assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(rawContent(perform(authenticated(get("/api/v1/payment-slips/{slipId}/notes", slipA.id()),
				tenantB.host(), tenantB.adminToken())))).doesNotContain("Tenant A only");
		assertThat(noteRowCount(slipA.id())).isEqualTo(1L);
	}

	@Test
	void slipNotesAreAppendOnlyAtTheDatabaseLevel() {
		SlipFixture f = seedTenantWithOrder("hub-note-append");
		PaymentSlipResponse slip = uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-APPEND",
				pdfFile("slip.pdf"));
		addNote(f.host(), f.adminToken(), slip.id(), "Original");

		assertThatThrownBy(
				() -> jdbcTemplate.update("UPDATE payment_slip_note SET body = 'Edited' WHERE slip_id = ?", slip.id()))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payment_slip_note WHERE slip_id = ?", slip.id()))
			.hasMessageContaining("append-only");
		assertThat(jdbcTemplate.queryForObject("SELECT body FROM payment_slip_note WHERE slip_id = ?", String.class,
				slip.id())).isEqualTo("Original");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private UUID seedStudentProfile(SlipFixture f, String name, String mobile) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, mobile, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?, now(), now())", id, f.tenant().getId(), f.student().getId(), name, mobile);
		return id;
	}

	private HttpResult<PageResponse<PaymentSlipResponse>> searchQueue(String host, String token, String search) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payment-slips/review-queue");
		if (search != null) {
			builder.param("search", search);
		}
		return parsePage(perform(authenticated(builder, host, token)), PaymentSlipResponse.class);
	}

	private static List<UUID> rowIds(HttpResult<PageResponse<PaymentSlipResponse>> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		return result.getBody().data().content().stream().map(PaymentSlipResponse::id).toList();
	}

	private static PaymentSlipResponse singleRow(HttpResult<PageResponse<PaymentSlipResponse>> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).hasSize(1);
		return result.getBody().data().content().get(0);
	}

	private HttpResult<SlipPendingCountResponse> pendingCount(String host, String token) {
		return parseSingle(perform(authenticated(get("/api/v1/payment-slips/review-queue/count"), host, token)),
				SlipPendingCountResponse.class);
	}

	private HttpResult<SlipNoteResponse> addNote(String host, String token, UUID slipId, String body) {
		String json = "{\"body\":\"" + body.replace("\"", "\\\"") + "\"}";
		MockHttpServletRequestBuilder builder = post("/api/v1/payment-slips/{slipId}/notes", slipId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		return parseSingle(perform(authenticated(builder, host, token)), SlipNoteResponse.class);
	}

	private HttpResult<List<SlipNoteResponse>> listNotes(String host, String token, UUID slipId) {
		return parseList(perform(authenticated(get("/api/v1/payment-slips/{slipId}/notes", slipId), host, token)),
				SlipNoteResponse.class);
	}

	private Long noteRowCount(UUID slipId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip_note WHERE slip_id = ?", Long.class,
				slipId);
	}

}
