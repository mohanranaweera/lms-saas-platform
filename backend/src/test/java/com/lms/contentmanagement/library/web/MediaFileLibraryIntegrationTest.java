package com.lms.contentmanagement.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi;
import com.lms.tenantmanagement.domain.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * R2-T3 (Klass gaps round 2 G1; R2-D1..R2-D3) Testcontainers/MockMvc coverage
 * of the institute media file library ({@code /api/v1/media-files}) and of
 * attaching a library file to a lesson ({@code POST .../materials} with
 * {@code mediaFileId}): authorization (students 403, teachers manage only their
 * own uploads, staff by the MATERIALS matrix), cross-tenant 404s, upload
 * sniffing / size with no partial write, audit rows, the delete-attached 409,
 * after-commit storage deletion, "detach never deletes the shared object", and
 * the lesson evaluator gating student downloads of library-backed materials.
 *
 * <p>{@code app.content.library.max-file-size-bytes} is lowered so the
 * oversize case needs no large payload; every fixture file is far smaller.
 */
@TestPropertySource(properties = "app.content.library.max-file-size-bytes=4096")
class MediaFileLibraryIntegrationTest extends ContentManagementTestSupport {

	@Autowired
	private InMemoryObjectStorageApi storage;

	private record Fx(Tenant tenant, String host, String adminToken, String teacherToken, String teacher2Token,
			String studentToken, String coordinatorToken, TenantUser teacher, TenantUser teacher2, UUID courseId,
			UUID moduleId, UUID lessonId, UUID lesson2Id) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacher2 = seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		var course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		var module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		var lesson2 = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 2", 2);
		return new Fx(tenant, host, adminToken, loginAndGetToken(host, "teacher@example.test"),
				loginAndGetToken(host, "teacher2@example.test"), loginAndGetToken(host, "student@example.test"),
				loginAndGetToken(host, "coordinator@example.test"), teacher, teacher2, course.id(), module.id(),
				lesson.id(), lesson2.id());
	}

	// ------------------------------------------------------------------
	// HTTP / SQL helpers.
	// ------------------------------------------------------------------

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult upload(String host, String token, MockMultipartFile file, String title) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/media-files").file(file);
		if (title != null) {
			builder.param("title", title);
		}
		return performMultipart(authenticatedMultipart(builder, host, token));
	}

	private UUID uploadOk(String host, String token, MockMultipartFile file, String title) {
		MvcResult result = upload(host, token, file, title);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private MvcResult list(String host, String token, String query) {
		return call(host, token, get("/api/v1/media-files" + query));
	}

	private MvcResult summary(String host, String token) {
		return call(host, token, get("/api/v1/media-files/summary"));
	}

	private MvcResult rename(String host, String token, UUID id, String title) {
		return call(host, token, patch("/api/v1/media-files/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("title", title))));
	}

	private MvcResult remove(String host, String token, UUID id) {
		return call(host, token, delete("/api/v1/media-files/{id}", id));
	}

	private MvcResult openUrl(String host, String token, UUID id) {
		return call(host, token, get("/api/v1/media-files/{id}/open-url", id));
	}

	private MvcResult attach(String host, String token, UUID courseId, UUID moduleId, UUID lessonId, String title,
			UUID mediaFileId) {
		MockMultipartHttpServletRequestBuilder builder = multipart(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials", courseId, moduleId,
				lessonId)
			.param("title", title)
			.param("mediaFileId", mediaFileId.toString());
		return performMultipart(authenticatedMultipart(builder, host, token));
	}

	private JsonNode attachOk(Fx fx, String token, UUID lessonId, String title, UUID mediaFileId) {
		MvcResult result = attach(fx.host(), token, fx.courseId(), fx.moduleId(), lessonId, title, mediaFileId);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private MvcResult downloadUrl(Fx fx, String token, UUID lessonId, UUID materialId) {
		return call(fx.host(), token,
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", fx.courseId(),
						fx.moduleId(), lessonId, materialId));
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private String objectKeyOf(UUID mediaFileId) {
		return jdbcTemplate.queryForObject("SELECT object_key FROM media_file WHERE id = ?", String.class,
				mediaFileId);
	}

	private long audits(UUID tenantId, String action, UUID targetId) {
		return count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND target_entity = "
				+ "'media_file' AND target_id = ?", tenantId, action, targetId);
	}

	private static List<String> ids(JsonNode page) {
		List<String> ids = new ArrayList<>();
		page.path("content").forEach(item -> ids.add(item.path("id").asString()));
		return ids;
	}

	private static JsonNode item(JsonNode page, UUID id) {
		for (JsonNode item : page.path("content")) {
			if (item.path("id").asString().equals(id.toString())) {
				return item;
			}
		}
		throw new AssertionError("item " + id + " not listed");
	}

	private static MockMultipartFile svgFile() {
		return new MockMultipartFile("file", "logo.svg", "image/svg+xml",
				"<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
					.getBytes(StandardCharsets.UTF_8));
	}

	private static MockMultipartFile xmlSvgFile() {
		return new MockMultipartFile("file", "logo.txt", "text/plain",
				"  <?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
					.getBytes(StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------
	// Upload, list, summary, open, audit.
	// ------------------------------------------------------------------

	@Test
	void teacherUploadsDocumentsAndImagesThenListsCountsOpensAndEveryUploadIsAudited() throws Exception {
		Fx fx = seed("lib-upload");

		UUID pdf = uploadOk(fx.host(), fx.teacherToken(), pdfFile("Week 1 Notes.pdf"), null);
		UUID png = uploadOk(fx.host(), fx.teacherToken(), pngFile("diagram.png"), "  Cell diagram ");
		UUID notes = uploadOk(fx.host(), fx.adminToken(), textNotesFile("reading.txt"), null);

		JsonNode page = json(list(fx.host(), fx.teacherToken(), "")).path("data");
		assertThat(ids(page)).containsExactly(notes.toString(), png.toString(), pdf.toString());
		JsonNode pdfItem = item(page, pdf);
		assertThat(pdfItem.path("kind").asString()).isEqualTo("DOCUMENT");
		assertThat(pdfItem.path("title").asString()).isEqualTo("Week 1 Notes");
		assertThat(pdfItem.path("originalFilename").asString()).isEqualTo("Week 1 Notes.pdf");
		assertThat(pdfItem.path("contentType").asString()).isEqualTo("application/pdf");
		assertThat(pdfItem.path("sizeBytes").asLong()).isEqualTo(validPdfBytes().length);
		assertThat(pdfItem.path("uploadedBy").asString()).isEqualTo(fx.teacher().getId().toString());
		assertThat(pdfItem.path("uploaderName").isNull()).isFalse();
		assertThat(pdfItem.path("attachedLessonCount").asInt()).isZero();
		assertThat(pdfItem.path("thumbnailUrl").isNull()).isTrue();
		assertThat(pdfItem.path("canEdit").asBoolean()).isTrue();
		assertThat(pdfItem.path("canDelete").asBoolean()).isTrue();
		JsonNode pngItem = item(page, png);
		assertThat(pngItem.path("kind").asString()).isEqualTo("IMAGE");
		assertThat(pngItem.path("title").asString()).isEqualTo("Cell diagram");
		assertThat(pngItem.path("thumbnailUrl").asString()).contains("response-content-type=image%2Fpng")
			.contains("response-content-disposition=inline");
		assertThat(item(page, notes).path("canEdit").asBoolean()).as("another uploader's file").isFalse();
		assertThat(rawContent(list(fx.host(), fx.teacherToken(), ""))).doesNotContain("objectKey")
			.doesNotContain("sha256")
			.doesNotContain(objectKeyOf(pdf));

		assertThat(ids(json(list(fx.host(), fx.teacherToken(), "?kind=IMAGE")).path("data")))
			.containsExactly(png.toString());
		assertThat(ids(json(list(fx.host(), fx.teacherToken(), "?q=WEEK")).path("data")))
			.containsExactly(pdf.toString());
		assertThat(ids(json(list(fx.host(), fx.teacherToken(), "?q=%25")).path("data"))).isEmpty(); // LIKE wildcard matched literally
		assertThat(status(list(fx.host(), fx.teacherToken(), "?size=101"))).isEqualTo(400);

		JsonNode counts = json(summary(fx.host(), fx.teacherToken())).path("data");
		assertThat(counts.path("documents").asLong()).isEqualTo(2);
		assertThat(counts.path("images").asLong()).isEqualTo(1);
		assertThat(counts.path("other").asLong()).isZero();
		assertThat(counts.path("total").asLong()).isEqualTo(3);

		MvcResult open = openUrl(fx.host(), fx.teacherToken(), pdf);
		assertThat(status(open)).as(rawContent(open)).isEqualTo(200);
		String url = json(open).path("data").path("url").asString();
		assertThat(url).contains(objectKeyOf(pdf))
			.contains("response-content-type=application%2Fpdf")
			.contains("response-content-disposition=inline");
		assertThat(json(open).path("data").path("expiresAt").isNull()).isFalse();

		// Stored once, sha256 lowercase hex of the exact bytes, every upload audited.
		assertThat(storage.containsKey(objectKeyOf(pdf))).isTrue();
		String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(validPdfBytes()));
		assertThat(jdbcTemplate.queryForObject("SELECT sha256 FROM media_file WHERE id = ?", String.class, pdf))
			.isEqualTo(expectedSha);
		assertThat(audits(fx.tenant().getId(), "media_file.uploaded", pdf)).isEqualTo(1);
		assertThat(audits(fx.tenant().getId(), "media_file.uploaded", png)).isEqualTo(1);
		assertThat(audits(fx.tenant().getId(), "media_file.uploaded", notes)).isEqualTo(1);
	}

	@Test
	void disguisedExecutableSvgAndOversizedUploadsAreRejectedWithNoStorageWriteAndNoRow() {
		Fx fx = seed("lib-reject");
		Set<String> keysBefore = storage.keySet();

		assertThat(status(upload(fx.host(), fx.teacherToken(), disguisedExecutableFile("notes.pdf"), null)))
			.isEqualTo(415);
		assertThat(status(upload(fx.host(), fx.teacherToken(), svgFile(), null))).isEqualTo(415);
		assertThat(status(upload(fx.host(), fx.teacherToken(), xmlSvgFile(), null))).isEqualTo(415);
		assertThat(status(upload(fx.host(), fx.teacherToken(), oversizedPdfFile("big.pdf", 8192), null)))
			.isEqualTo(413);
		assertThat(status(upload(fx.host(), fx.teacherToken(),
				new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]), null)))
			.isEqualTo(400);
		assertThat(status(upload(fx.host(), fx.teacherToken(), pdfFile("a.pdf"), "x".repeat(256)))).isEqualTo(400);

		assertThat(storage.keySet()).isEqualTo(keysBefore);
		assertThat(count("SELECT count(*) FROM media_file WHERE tenant_id = ?", fx.tenant().getId())).isZero();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'media_file.uploaded'",
				fx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Authorization.
	// ------------------------------------------------------------------

	@Test
	void studentIsForbiddenOnEveryLibraryEndpoint() {
		Fx fx = seed("lib-student");
		UUID file = uploadOk(fx.host(), fx.teacherToken(), pdfFile("notes.pdf"), null);
		Set<String> keysBefore = storage.keySet();

		assertThat(status(list(fx.host(), fx.studentToken(), ""))).isEqualTo(403);
		assertThat(status(summary(fx.host(), fx.studentToken()))).isEqualTo(403);
		assertThat(status(upload(fx.host(), fx.studentToken(), pdfFile("mine.pdf"), null))).isEqualTo(403);
		assertThat(status(rename(fx.host(), fx.studentToken(), file, "Renamed"))).isEqualTo(403);
		assertThat(status(remove(fx.host(), fx.studentToken(), file))).isEqualTo(403);
		assertThat(status(openUrl(fx.host(), fx.studentToken(), file))).isEqualTo(403);

		assertThat(storage.keySet()).isEqualTo(keysBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT title FROM media_file WHERE id = ?", String.class, file))
			.isEqualTo("notes");
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND deleted_at IS NULL", file)).isEqualTo(1);
	}

	@Test
	void teacherListsOpensAndAttachesAnotherTeachersFileButCannotRenameOrDeleteIt() {
		Fx fx = seed("lib-teacher-own");
		UUID othersFile = uploadOk(fx.host(), fx.teacher2Token(), pdfFile("teacher2.pdf"), null);

		JsonNode listed = item(json(list(fx.host(), fx.teacherToken(), "")).path("data"), othersFile);
		assertThat(listed.path("canEdit").asBoolean()).isFalse();
		assertThat(listed.path("canDelete").asBoolean()).isFalse();
		assertThat(status(openUrl(fx.host(), fx.teacherToken(), othersFile))).isEqualTo(200);

		assertThat(status(rename(fx.host(), fx.teacherToken(), othersFile, "Mine now"))).isEqualTo(404);
		assertThat(status(remove(fx.host(), fx.teacherToken(), othersFile))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT title FROM media_file WHERE id = ?", String.class, othersFile))
			.isEqualTo("teacher2");
		assertThat(audits(fx.tenant().getId(), "media_file.renamed", othersFile)).isZero();

		// The course teacher attaches it to their own lesson.
		JsonNode material = attachOk(fx, fx.teacherToken(), fx.lessonId(), "Shared notes", othersFile);
		assertThat(material.path("mediaFileId").asString()).isEqualTo(othersFile.toString());

		// ... but teacher2 cannot attach anything to a course they do not teach (existing gate).
		UUID own = uploadOk(fx.host(), fx.teacher2Token(), pngFile("t2.png"), null);
		assertThat(status(attach(fx.host(), fx.teacher2Token(), fx.courseId(), fx.moduleId(), fx.lessonId(), "X",
				own))).isEqualTo(403);
	}

	@Test
	void renameIsAuditedWithBeforeAndAfterAndStaffFollowTheMaterialsMatrix() {
		Fx fx = seed("lib-rename");
		UUID file = uploadOk(fx.host(), fx.teacherToken(), pdfFile("draft.pdf"), null);

		MvcResult renamed = rename(fx.host(), fx.teacherToken(), file, "  Final notes ");
		assertThat(status(renamed)).as(rawContent(renamed)).isEqualTo(200);
		assertThat(json(renamed).path("data").path("title").asString()).isEqualTo("Final notes");
		String metadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? "
				+ "AND action = 'media_file.renamed' AND target_id = ?", String.class, fx.tenant().getId(), file);
		assertThat(metadata).contains("\"from\": \"draft\"").contains("\"to\": \"Final notes\"");
		assertThat(status(rename(fx.host(), fx.teacherToken(), file, "   "))).isEqualTo(400);

		// Course Coordinator: MATERIALS VIEW only.
		assertThat(status(list(fx.host(), fx.coordinatorToken(), ""))).isEqualTo(200);
		assertThat(item(json(list(fx.host(), fx.coordinatorToken(), "")).path("data"), file).path("canEdit")
			.asBoolean()).isFalse();
		assertThat(status(openUrl(fx.host(), fx.coordinatorToken(), file))).isEqualTo(200);
		assertThat(status(upload(fx.host(), fx.coordinatorToken(), pdfFile("c.pdf"), null))).isEqualTo(403);
		assertThat(status(rename(fx.host(), fx.coordinatorToken(), file, "Nope"))).isEqualTo(403);
		assertThat(status(remove(fx.host(), fx.coordinatorToken(), file))).isEqualTo(403);

		// Tenant Admin manages any file.
		assertThat(status(rename(fx.host(), fx.adminToken(), file, "Admin title"))).isEqualTo(200);
		assertThat(audits(fx.tenant().getId(), "media_file.renamed", file)).isEqualTo(2);
	}

	// ------------------------------------------------------------------
	// Cross-tenant isolation (mandatory).
	// ------------------------------------------------------------------

	@Tag("cross-tenant")
	@Test
	void anotherTenantsFileIsNotFoundOnEveryEndpointAndCannotBeAttached() {
		Fx a = seed("lib-iso-a");
		Fx b = seed("lib-iso-b");
		UUID aFile = uploadOk(a.host(), a.teacherToken(), pngFile("a.png"), null);
		UUID bFile = uploadOk(b.host(), b.teacherToken(), pdfFile("b.pdf"), null);

		assertThat(ids(json(list(b.host(), b.adminToken(), "")).path("data"))).containsExactly(bFile.toString());
		assertThat(ids(json(list(b.host(), b.adminToken(), "?tenantId=" + a.tenant().getId())).path("data")))
			.containsExactly(bFile.toString());
		assertThat(json(summary(b.host(), b.adminToken())).path("data").path("total").asLong()).isEqualTo(1);
		for (String token : List.of(b.adminToken(), b.teacherToken())) {
			assertThat(status(openUrl(b.host(), token, aFile))).isEqualTo(404);
			assertThat(status(rename(b.host(), token, aFile, "Stolen"))).isEqualTo(404);
			assertThat(status(remove(b.host(), token, aFile))).isEqualTo(404);
		}
		assertThat(status(attach(b.host(), b.adminToken(), b.courseId(), b.moduleId(), b.lessonId(), "Stolen",
				aFile))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM material WHERE media_file_id = ?", aFile)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT title FROM media_file WHERE id = ?", String.class, aFile))
			.isEqualTo("a");
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND deleted_at IS NULL", aFile)).isEqualTo(1);
		assertThat(storage.containsKey(objectKeyOf(aFile))).isTrue();
	}

	// ------------------------------------------------------------------
	// Attach / detach / delete.
	// ------------------------------------------------------------------

	@Test
	void attachingCopiesMetadataStoresNoObjectKeyMapsTheTypeAndRejectsADuplicate() {
		Fx fx = seed("lib-attach");
		UUID pdf = uploadOk(fx.host(), fx.adminToken(), pdfFile("handout.pdf"), null);
		UUID png = uploadOk(fx.host(), fx.adminToken(), pngFile("chart.png"), null);
		UUID txt = uploadOk(fx.host(), fx.adminToken(), textNotesFile("notes.txt"), null);

		JsonNode pdfMaterial = attachOk(fx, fx.teacherToken(), fx.lessonId(), "Handout", pdf);
		assertThat(pdfMaterial.path("materialType").asString()).isEqualTo("PDF");
		assertThat(pdfMaterial.path("mimeType").asString()).isEqualTo("application/pdf");
		assertThat(pdfMaterial.path("originalFilename").asString()).isEqualTo("handout.pdf");
		assertThat(pdfMaterial.path("sizeBytes").asLong()).isEqualTo(validPdfBytes().length);
		assertThat(attachOk(fx, fx.teacherToken(), fx.lessonId(), "Chart", png).path("materialType").asString())
			.isEqualTo("IMAGE");
		assertThat(attachOk(fx, fx.teacherToken(), fx.lessonId(), "Notes", txt).path("materialType").asString())
			.isEqualTo("DOCUMENT");
		UUID materialId = UUID.fromString(pdfMaterial.path("id").asString());
		assertThat(jdbcTemplate.queryForObject("SELECT storage_object_key FROM material WHERE id = ?", String.class,
				materialId))
			.isNull();

		// Same lesson again -> 409; a different lesson is fine (no copy either way).
		Set<String> keysBefore = storage.keySet();
		MvcResult duplicate = attach(fx.host(), fx.teacherToken(), fx.courseId(), fx.moduleId(), fx.lessonId(),
				"Again", pdf);
		assertThat(status(duplicate)).isEqualTo(409);
		attachOk(fx, fx.teacherToken(), fx.lesson2Id(), "Handout", pdf);
		assertThat(storage.keySet()).isEqualTo(keysBefore);
		assertThat(item(json(list(fx.host(), fx.adminToken(), "")).path("data"), pdf).path("attachedLessonCount")
			.asInt()).isEqualTo(2);

		// mediaFileId with a file part, or with a non-file type, is a 400.
		MockMultipartHttpServletRequestBuilder both = multipart(
				"/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials", fx.courseId(), fx.moduleId(),
				fx.lesson2Id())
			.file(pdfFile("x.pdf"))
			.param("title", "Both")
			.param("mediaFileId", png.toString());
		assertThat(status(performMultipart(authenticatedMultipart(both, fx.host(), fx.teacherToken()))))
			.isEqualTo(400);
		MockMultipartHttpServletRequestBuilder asLink = multipart(
				"/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials", fx.courseId(), fx.moduleId(),
				fx.lesson2Id())
			.param("title", "Link")
			.param("materialType", "LINK")
			.param("mediaFileId", png.toString());
		assertThat(status(performMultipart(authenticatedMultipart(asLink, fx.host(), fx.teacherToken()))))
			.isEqualTo(400);
		assertThat(storage.keySet()).isEqualTo(keysBefore);
	}

	@Test
	void deletingAnAttachedFileIsRefusedAndDetachNeverDeletesTheSharedObjectWhileALaterDeleteRemovesItAfterCommit() {
		Fx fx = seed("lib-delete");
		UUID file = uploadOk(fx.host(), fx.adminToken(), pdfFile("shared.pdf"), null);
		String objectKey = objectKeyOf(file);
		UUID materialId = UUID.fromString(attachOk(fx, fx.teacherToken(), fx.lessonId(), "Shared", file)
			.path("id")
			.asString());

		MvcResult refused = remove(fx.host(), fx.adminToken(), file);
		assertThat(status(refused)).isEqualTo(409);
		assertThat(json(refused).path("error").path("code").asString()).isEqualTo("MEDIA_ATTACHED");
		assertThat(json(refused).path("error").path("message").asString()).contains("1 lesson material");
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND deleted_at IS NULL", file)).isEqualTo(1);
		assertThat(audits(fx.tenant().getId(), "media_file.deleted", file)).isZero();

		// Detach (delete the lesson material): the shared library object must survive.
		assertThat(deleteMaterial(fx.host(), fx.teacherToken(), fx.courseId(), fx.moduleId(), fx.lessonId(),
				materialId)
			.getStatusCode()
			.value()).isEqualTo(200);
		assertThat(storage.containsKey(objectKey)).isTrue();
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND deleted_at IS NULL", file)).isEqualTo(1);

		// Now unattached: soft delete, audited, object removed after commit.
		MvcResult deleted = remove(fx.host(), fx.adminToken(), file);
		assertThat(status(deleted)).as(rawContent(deleted)).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND deleted_at IS NOT NULL "
				+ "AND deleted_by IS NOT NULL", file)).isEqualTo(1);
		assertThat(audits(fx.tenant().getId(), "media_file.deleted", file)).isEqualTo(1);
		assertThat(storage.containsKey(objectKey)).isFalse();

		// Gone from the library: list, summary, open, rename, delete again, attach.
		assertThat(ids(json(list(fx.host(), fx.adminToken(), "")).path("data"))).doesNotContain(file.toString());
		assertThat(json(summary(fx.host(), fx.adminToken())).path("data").path("total").asLong()).isZero();
		assertThat(status(openUrl(fx.host(), fx.adminToken(), file))).isEqualTo(404);
		assertThat(status(rename(fx.host(), fx.adminToken(), file, "Back"))).isEqualTo(404);
		assertThat(status(remove(fx.host(), fx.adminToken(), file))).isEqualTo(404);
		assertThat(status(attach(fx.host(), fx.teacherToken(), fx.courseId(), fx.moduleId(), fx.lessonId(), "Re",
				file))).isEqualTo(404);
	}

	@Test
	void teacherDeletesTheirOwnUnattachedUpload() {
		Fx fx = seed("lib-own-delete");
		UUID file = uploadOk(fx.host(), fx.teacherToken(), pngFile("mine.png"), null);
		String objectKey = objectKeyOf(file);

		assertThat(status(remove(fx.host(), fx.teacherToken(), file))).isEqualTo(200);

		assertThat(storage.containsKey(objectKey)).isFalse();
		assertThat(audits(fx.tenant().getId(), "media_file.deleted", file)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// Student downloads of a library-backed material (ADR-028 evaluator).
	// ------------------------------------------------------------------

	@Tag("cross-tenant")
	@Test
	void studentCannotDownloadAnotherTenantsLibraryBackedMaterial() {
		Fx a = seed("lib-student-iso-a");
		Fx b = seed("lib-student-iso-b");
		UUID file = uploadOk(a.host(), a.adminToken(), pdfFile("private.pdf"), null);
		String key = objectKeyOf(file);
		UUID materialId = UUID.fromString(attachOk(a, a.teacherToken(), a.lessonId(), "Private", file)
			.path("id")
			.asString());
		enrollStudentOrFail(b.host(), b.studentToken(), b.courseId());
		MvcResult foreignPath = call(b.host(), b.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", a.courseId(),
						a.moduleId(), a.lessonId(), materialId));
		MvcResult ownPath = downloadUrl(b, b.studentToken(), b.lessonId(), materialId);
		for (MvcResult result : List.of(foreignPath, ownPath)) {
			assertThat(status(result)).isEqualTo(404);
			assertThat(json(result).path("data").isNull()).isTrue();
			assertThat(rawContent(result)).doesNotContain(key).doesNotContain("\"url\"");
		}
		assertThat(count("SELECT download_count FROM material WHERE id = ?", materialId)).isZero();
		assertThat(storage.containsKey(key)).isTrue();
	}

	@Test
	void studentDownloadOfALibraryBackedMaterialIsGatedByTheLessonEvaluator() {
		Fx fx = seed("lib-download");
		UUID file = uploadOk(fx.host(), fx.adminToken(), pngFile("lesson.png"), null);
		String objectKey = objectKeyOf(file);
		JsonNode imageMaterial = attachOk(fx, fx.teacherToken(), fx.lessonId(), "Lesson image", file);
		assertThat(imageMaterial.path("materialType").asString()).isEqualTo("IMAGE");
		UUID materialId = UUID.fromString(imageMaterial.path("id").asString());
		seedActiveStudent(fx.tenant().getId(), "paid@example.test");
		String paidToken = loginAndGetToken(fx.host(), "paid@example.test");

		// Not enrolled: 404, nothing counted.
		assertThat(status(downloadUrl(fx, fx.studentToken(), fx.lessonId(), materialId))).isEqualTo(404);

		// Free (unpaid) participant: listed, but the protected download is refused.
		MvcResult enrolled = call(fx.host(), fx.studentToken(),
				post("/api/v1/enrollments/my/courses/{courseId}", fx.courseId()));
		assertThat(status(enrolled)).isEqualTo(200);
		MvcResult unpaid = downloadUrl(fx, fx.studentToken(), fx.lessonId(), materialId);
		assertThat(status(unpaid)).isEqualTo(403);
		assertThat(rawContent(unpaid)).doesNotContain(objectKey);
		assertThat(listMaterials(fx.host(), fx.studentToken(), fx.courseId(), fx.moduleId(), fx.lessonId())
			.getBody()
			.data()
			.get(0)
			.mediaFileId()).isEqualTo(file);

		// Paid participant: a signed URL for the shared library object; the count moves.
		enrollStudentOrFail(fx.host(), paidToken, fx.courseId());
		MvcResult paid = downloadUrl(fx, paidToken, fx.lessonId(), materialId);
		assertThat(status(paid)).as(rawContent(paid)).isEqualTo(200);
		assertThat(json(paid).path("data").path("url").asString()).contains(objectKey);
		assertThat(count("SELECT download_count FROM material WHERE id = ?", materialId)).isEqualTo(1);

		// The library endpoints themselves stay closed to the paid student.
		assertThat(status(openUrl(fx.host(), paidToken, file))).isEqualTo(403);
	}

}
