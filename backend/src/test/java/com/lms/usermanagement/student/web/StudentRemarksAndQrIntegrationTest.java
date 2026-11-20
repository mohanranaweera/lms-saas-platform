package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.lms.identityaccessservice.domain.Role;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STU-MGMT-02 remarks (staff only, plain text, date filter, cross-tenant 404) and the
 * student QR (spec section 7): the PNG decodes to {@code LMS-STU:1:<token>}, rotation
 * revokes the old payload (audited), the resolver is staff + same tenant only with no
 * side effects, and the label PDF contains exactly the selected students of this tenant
 * and never a NIC.
 */
class StudentRemarksAndQrIntegrationTest extends StudentManagementTestSupport {

	@Test
	void remarksAreStaffOnlyPlainTextFilteredAndTenantScoped() {
		Fx fx = fixture("stu-rmk");
		UUID id = createStudentId(fx, "Remark", "Target");
		String studentToken = studentToken(fx, id);

		String body = "<b>Called parent</b> & agreed to pay next week";
		MvcResult added = send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id),
				Map.of("body", body));
		assertThat(status(added)).as(rawContent(added)).isEqualTo(201);
		assertThat(data(added).path("body").asString()).isEqualTo(body);
		assertThat(data(added).path("authorName").asString()).isEqualTo("admin@example.test");
		assertThat(data(added).path("createdAt").isNull()).isFalse();
		send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id), Map.of("body", "Second remark"));

		JsonNode page = data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/remarks", id));
		assertThat(page.path("totalElements").asLong()).isEqualTo(2);
		// Newest first.
		assertThat(page.path("content").get(0).path("body").asString()).isEqualTo("Second remark");
		// Stored exactly as plain text.
		assertThat(jdbcTemplate.queryForList("SELECT body FROM student_remark WHERE student_profile_id = ?",
				String.class, id)).contains(body);

		// Date range filter.
		String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();
		MvcResult fromFuture = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/remarks?from={from}", id,
				future);
		assertThat(status(fromFuture)).as(rawContent(fromFuture)).isEqualTo(200);
		assertThat(data(fromFuture).path("totalElements").asLong()).isZero();
		MvcResult toFuture = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/remarks?to={to}", id, future);
		assertThat(status(toFuture)).as(rawContent(toFuture)).isEqualTo(200);
		assertThat(data(toFuture).path("totalElements").asLong()).isEqualTo(2);

		// Validation.
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id),
				Map.of("body", "   ")))).isEqualTo(400);
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id),
				Map.of("body", "x".repeat(2001))))).isEqualTo(400);

		// Students and teachers never see remarks; view-only staff may read but not add.
		assertThat(status(getAs(fx.host(), studentToken, "/api/v1/students/{id}/remarks", id))).isEqualTo(403);
		assertThat(status(send(fx.host(), studentToken, post("/api/v1/students/{id}/remarks", id),
				Map.of("body", "self note")))).isEqualTo(403);
		String teacherToken = loginAndGetToken(fx.host(), "teacher@example.test");
		assertThat(status(getAs(fx.host(), teacherToken, "/api/v1/students/{id}/remarks", id))).isEqualTo(403);
		String auditor = staffToken(fx, Role.READ_ONLY_AUDITOR);
		assertThat(status(getAs(fx.host(), auditor, "/api/v1/students/{id}/remarks", id))).isEqualTo(200);
		assertThat(status(send(fx.host(), auditor, post("/api/v1/students/{id}/remarks", id),
				Map.of("body", "auditor note")))).isEqualTo(403);
		assertThat(rawContent(getAs(fx.host(), studentToken, "/api/v1/students/me"))).doesNotContain("Called parent");

		// Another tenant: 404 both ways, nothing written.
		Fx other = fixture("stu-rmk-other");
		assertThat(status(getAs(other.host(), other.adminToken(), "/api/v1/students/{id}/remarks", id)))
			.isEqualTo(404);
		assertThat(status(send(other.host(), other.adminToken(), post("/api/v1/students/{id}/remarks", id),
				Map.of("body", "cross tenant")))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM student_remark WHERE student_profile_id = ?", id)).isEqualTo(2);
	}

	private static String decode(byte[] png) throws Exception {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
		return new QRCodeReader().decode(bitmap).getText();
	}

	@Test
	void qrPngDecodesToTheOpaquePayloadRotationRevokesAndResolveIsTenantScoped() throws Exception {
		Fx fx = fixture("stu-qr");
		Map<String, Object> form = form(fx, "Qr", "Holder");
		form.put("guardianNic", "198812345678");
		UUID id = UUID.fromString(createStudent(fx, form).path("id").asString());

		MvcResult qr = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/qr", id);
		assertThat(status(qr)).isEqualTo(200);
		String payload = data(qr).path("payload").asString();
		assertThat(payload).matches("LMS-STU:1:[0-9a-f]{48}");
		assertThat(data(qr).path("rotatedAt").isNull()).isTrue();
		String index = jdbcTemplate.queryForObject("SELECT index_number FROM student_profile WHERE id = ?",
				String.class, id);
		assertThat(payload).doesNotContain(index).doesNotContain("Holder").doesNotContain("198812345678");

		MvcResult png = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/qr.png", id);
		assertThat(status(png)).isEqualTo(200);
		assertThat(png.getResponse().getContentType()).isEqualTo("image/png");
		assertThat(decode(png.getResponse().getContentAsByteArray())).isEqualTo(payload);

		// Resolve: staff of this tenant only; no attendance side effects.
		String resolvePath = "/api/v1/students/qr/resolve?payload={payload}";
		MvcResult resolved = getAs(fx.host(), fx.adminToken(), resolvePath, payload);
		assertThat(status(resolved)).isEqualTo(200);
		assertThat(data(resolved).path("id").asString()).isEqualTo(id.toString());
		assertThat(data(resolved).path("indexNumber").asString()).isEqualTo(index);
		assertThat(data(resolved).path("fullName").asString()).isEqualTo("Qr Holder");
		assertThat(count("SELECT count(*) FROM attendance_record WHERE tenant_id = ?", fx.tenant().getId())).isZero();
		Fx other = fixture("stu-qr-other");
		assertThat(status(getAs(other.host(), other.adminToken(), resolvePath, payload))).isEqualTo(404);
		assertThat(status(getAs(other.host(), other.adminToken(), "/api/v1/students/{id}/qr", id))).isEqualTo(404);
		assertThat(status(getAs(other.host(), other.adminToken(), "/api/v1/students/{id}/qr.png", id))).isEqualTo(404);
		assertThat(status(getAs(fx.host(), null, resolvePath, payload))).isEqualTo(401);
		assertThat(status(getAs(fx.host(), studentToken(fx, id), resolvePath, payload))).isEqualTo(403);
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/students/qr/resolve?payload=garbage")))
			.isEqualTo(404);

		// Rotation: new payload, old one no longer resolves, audited without token values.
		MvcResult rotated = send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/qr/rotate", id), null);
		assertThat(status(rotated)).isEqualTo(200);
		String newPayload = data(rotated).path("payload").asString();
		assertThat(newPayload).matches("LMS-STU:1:[0-9a-f]{48}").isNotEqualTo(payload);
		assertThat(status(getAs(fx.host(), fx.adminToken(), resolvePath, payload))).isEqualTo(404);
		assertThat(status(getAs(fx.host(), fx.adminToken(), resolvePath, newPayload))).isEqualTo(200);
		assertThat(data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/qr", id)).path("rotatedAt").isNull())
			.isFalse();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.qr_rotated' "
				+ "AND target_id = ?", fx.tenant().getId(), id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND metadata::text LIKE ?",
				fx.tenant().getId(), "%" + newPayload.substring(10) + "%")).isZero();
		String auditor = staffToken(fx, Role.READ_ONLY_AUDITOR);
		assertThat(status(send(fx.host(), auditor, post("/api/v1/students/{id}/qr/rotate", id), null)))
			.isEqualTo(403);
	}

	private static String pdfText(byte[] pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder text = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page)).append('\n');
			}
			return text.toString();
		}
		finally {
			reader.close();
		}
	}

	@Test
	void labelPdfContainsExactlyTheSelectedStudentsOfThisTenant() throws Exception {
		Fx fx = fixture("stu-lbl");
		List<UUID> selected = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Map<String, Object> form = form(fx, "Chosen" + i, "Label");
			form.put("guardianNic", "19901234567" + i);
			selected.add(UUID.fromString(createStudent(fx, form).path("id").asString()));
		}
		createStudentId(fx, "Unselected", "Student");
		Fx other = fixture("stu-lbl-other");
		UUID foreign = createStudentId(other, "Foreign", "Student");

		List<String> ids = new ArrayList<>();
		selected.forEach(uuid -> ids.add(uuid.toString()));
		ids.add(foreign.toString());
		MvcResult pdf = send(fx.host(), fx.adminToken(), post("/api/v1/students/qr-export"), Map.of("studentIds", ids));
		assertThat(status(pdf)).as(rawContent(pdf)).isEqualTo(200);
		assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
		byte[] bytes = pdf.getResponse().getContentAsByteArray();
		assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
		String text = pdfText(bytes);
		assertThat(text).contains("Chosen0 Label", "Chosen1 Label", "Chosen2 Label");
		// Long names wrap inside the label cell.
		assertThat(text.replaceAll("\\s+", "")).contains(fx.tenant().getName().replaceAll("\\s+", ""));
		for (UUID uuid : selected) {
			assertThat(text).contains(jdbcTemplate.queryForObject("SELECT index_number FROM student_profile WHERE id = ?",
					String.class, uuid));
		}
		assertThat(text).doesNotContain("Unselected").doesNotContain("Foreign").doesNotContain("1990123456");

		// More than 500 ids are refused; students and anonymous callers are refused.
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i < 501; i++) {
			tooMany.add(UUID.randomUUID().toString());
		}
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/qr-export"),
				Map.of("studentIds", tooMany)))).isEqualTo(400);
		assertThat(status(send(fx.host(), studentToken(fx, selected.get(0)), post("/api/v1/students/qr-export"),
				Map.of("studentIds", ids)))).isEqualTo(403);
		assertThat(status(send(fx.host(), null, post("/api/v1/students/qr-export"), Map.of("studentIds", ids))))
			.isEqualTo(401);
	}

}
