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
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * MEDIA-FOLDERS-01 Testcontainers/MockMvc coverage of Media Library folders ({@code
 * /api/v1/media-folders}) and the folder-aware {@code /api/v1/media-files} changes: tree listing
 * and disclosure, name rules, file moves that keep identity and lesson attachments, cycle and
 * empty-only delete rules, cross-tenant 404s, role / grant boundaries (hidden = 404, never
 * counted), ownership transfer and the concurrent cycle race. Synthetic fixtures only.
 */
class MediaFolderIntegrationTest extends ContentManagementTestSupport {

	private static final String MANAGE = "MEDIA_MANAGE_FOLDERS";

	private static final String SHARE = "MEDIA_SHARE_FOLDERS";

	private record Fx(Tenant tenant, String host, String adminToken, TenantUser admin, String t1Token,
			TenantUser t1, String t2Token, TenantUser t2, String t3Token, TenantUser t3, String cmToken,
			TenantUser cm, UUID courseId, UUID moduleId, UUID lessonId) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		UUID tenantId = tenant.getId();
		TenantUser admin = seedTenantUser(tenantId, "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser t1 = seedTenantUser(tenantId, "t1@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser t2 = seedTenantUser(tenantId, "t2@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser t3 = seedTenantUser(tenantId, "t3@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser cm = seedTenantUser(tenantId, "cm@example.test", RAW_PASSWORD, Role.CONTENT_MANAGER);
		seedApprovedTeacherProfile(tenantId, t1.getId(), "Teacher One");
		seedApprovedTeacherProfile(tenantId, t2.getId(), "Teacher Two");
		seedApprovedTeacherProfile(tenantId, t3.getId(), "Teacher Three");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		var course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), t1.getId(), CourseStatus.PUBLIC));
		var module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		return new Fx(tenant, host, adminToken, admin, loginAndGetToken(host, "t1@example.test"), t1,
				loginAndGetToken(host, "t2@example.test"), t2, loginAndGetToken(host, "t3@example.test"), t3,
				loginAndGetToken(host, "cm@example.test"), cm, course.id(), module.id(), lesson.id());
	}

	private void teachersMayManageAndShare(Fx fx) {
		setRoleCapability(fx.tenant().getId(), "TEACHER", MANAGE, true);
		setRoleCapability(fx.tenant().getId(), "TEACHER", SHARE, true);
	}

	// ------------------------------------------------------------------
	// HTTP helpers.
	// ------------------------------------------------------------------

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult jsonCall(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return call(host, token, builder.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private MvcResult createFolder(String host, String token, String name, UUID parentId) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("parentId", parentId);
		return jsonCall(host, token, post("/api/v1/media-folders"), body);
	}

	private UUID folder(String host, String token, String name, UUID parentId) {
		MvcResult result = createFolder(host, token, name, parentId);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private MvcResult listFolders(String host, String token, UUID parentId) {
		return call(host, token, get("/api/v1/media-folders" + (parentId == null ? "" : "?parentId=" + parentId)));
	}

	private MvcResult getFolder(String host, String token, UUID id) {
		return call(host, token, get("/api/v1/media-folders/{id}", id));
	}

	private MvcResult renameFolder(String host, String token, UUID id, String name, Long expectedVersion) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("expectedVersion", expectedVersion);
		return jsonCall(host, token, patch("/api/v1/media-folders/{id}", id), body);
	}

	private MvcResult moveFolder(String host, String token, UUID id, UUID parentId) {
		Map<String, Object> body = new HashMap<>();
		body.put("parentId", parentId);
		return jsonCall(host, token, post("/api/v1/media-folders/{id}/move", id), body);
	}

	private MvcResult deleteFolder(String host, String token, UUID id, Long expectedVersion) {
		return call(host, token, delete("/api/v1/media-folders/{id}" + (expectedVersion == null ? ""
				: "?expectedVersion=" + expectedVersion), id));
	}

	private MvcResult listGrants(String host, String token, UUID id) {
		return call(host, token, get("/api/v1/media-folders/{id}/grants", id));
	}

	private MvcResult grant(String host, String token, UUID id, String level, UUID... userIds) {
		Map<String, Object> body = new HashMap<>();
		body.put("userIds", List.of(userIds));
		body.put("accessLevel", level);
		return jsonCall(host, token, post("/api/v1/media-folders/{id}/grants", id), body);
	}

	private void grantOk(Fx fx, UUID folderId, String level, UUID... userIds) {
		MvcResult result = grant(fx.host(), fx.adminToken(), folderId, level, userIds);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	private MvcResult revoke(String host, String token, UUID id, UUID userId) {
		return call(host, token, delete("/api/v1/media-folders/{id}/grants/{userId}", id, userId));
	}

	private MvcResult transfer(String host, String token, UUID id, UUID newOwnerId, String retention) {
		Map<String, Object> body = new HashMap<>();
		body.put("newOwnerId", newOwnerId);
		body.put("formerOwnerAccess", retention);
		return jsonCall(host, token, post("/api/v1/media-folders/{id}/transfer", id), body);
	}

	private MvcResult upload(String host, String token, MockMultipartFile file, String title, UUID folderId) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/media-files").file(file);
		builder.param("title", title);
		if (folderId != null) {
			builder.param("folderId", folderId.toString());
		}
		return performMultipart(authenticatedMultipart(builder, host, token));
	}

	private UUID uploadOk(String host, String token, String title, UUID folderId) {
		MvcResult result = upload(host, token, pdfFile(title + ".pdf"), title, folderId);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private MvcResult moveFile(String host, String token, UUID id, UUID folderId) {
		Map<String, Object> body = new HashMap<>();
		body.put("folderId", folderId);
		return jsonCall(host, token, post("/api/v1/media-files/{id}/move", id), body);
	}

	private MvcResult listFiles(String host, String token, String query) {
		return call(host, token, get("/api/v1/media-files" + query));
	}

	private MvcResult summary(String host, String token) {
		return call(host, token, get("/api/v1/media-files/summary"));
	}

	private MvcResult uploaders(String host, String token, String query) {
		return call(host, token, get("/api/v1/media-files/uploaders" + query));
	}

	private MvcResult openUrl(String host, String token, UUID id) {
		return call(host, token, get("/api/v1/media-files/{id}/open-url", id));
	}

	private MvcResult attach(Fx fx, String token, String title, UUID mediaFileId) {
		MockMultipartHttpServletRequestBuilder builder = multipart(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials", fx.courseId(),
				fx.moduleId(), fx.lessonId())
			.param("title", title)
			.param("mediaFileId", mediaFileId.toString());
		return performMultipart(authenticatedMultipart(builder, fx.host(), token));
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private String code(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	private String firstFieldError(MvcResult result) {
		return json(result).path("error").path("fieldErrors").path(0).path("field").asString();
	}

	private static List<String> ids(JsonNode array) {
		List<String> ids = new ArrayList<>();
		array.forEach(item -> ids.add(item.path("id").asString()));
		return ids;
	}

	private static List<String> names(JsonNode array) {
		List<String> names = new ArrayList<>();
		array.forEach(item -> names.add(item.path("name").asString()));
		return names;
	}

	private static List<String> userIds(JsonNode array) {
		List<String> ids = new ArrayList<>();
		array.forEach(item -> ids.add(item.path("userId").asString()));
		return ids;
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private long folderAudits(UUID tenantId, String action, UUID folderId) {
		return count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND target_entity = "
				+ "'media_folder' AND target_id = ?", tenantId, action, folderId);
	}

	private UUID ownerOf(UUID folderId) {
		return jdbcTemplate.queryForObject("SELECT owner_user_id FROM media_folder WHERE id = ?", UUID.class, folderId);
	}

	private UUID parentOf(UUID folderId) {
		return jdbcTemplate.queryForObject("SELECT parent_id FROM media_folder WHERE id = ?", UUID.class, folderId);
	}

	private String grantLevel(UUID folderId, UUID userId) {
		List<String> levels = jdbcTemplate.queryForList(
				"SELECT access_level FROM media_folder_grant WHERE folder_id = ? AND grantee_user_id = ?",
				String.class, folderId, userId);
		return levels.isEmpty() ? null : levels.get(0);
	}

	// ------------------------------------------------------------------
	// 1. Tree listing, detail / breadcrumb and name rules.
	// ------------------------------------------------------------------

	@Test
	void rootAndChildListingDetailBreadcrumbAndNameValidation() {
		Fx fx = seed("mf-tree");
		String host = fx.host();
		String admin = fx.adminToken();
		UUID science = folder(host, admin, "Science", null);
		UUID physics = folder(host, admin, "Physics", science);
		UUID chem = folder(host, admin, "Chem", science);

		JsonNode root = json(listFolders(host, admin, null)).path("data");
		assertThat(ids(root)).containsExactly(science.toString());
		assertThat(root.path(0).path("childFolderCount").asInt()).isEqualTo(2);
		assertThat(root.path(0).path("access").asString()).isEqualTo("OWNER");
		assertThat(root.path(0).path("canManage").asBoolean()).isTrue();
		assertThat(root.path(0).path("parentId").isNull()).isTrue();
		assertThat(names(json(listFolders(host, admin, science)).path("data"))).containsExactly("Chem", "Physics");

		JsonNode detail = json(getFolder(host, admin, physics)).path("data");
		assertThat(detail.path("parentId").asString()).isEqualTo(science.toString());
		assertThat(ids(detail.path("breadcrumb"))).containsExactly(science.toString());
		assertThat(detail.path("fileCount").asLong()).isZero();
		assertThat(status(getFolder(host, admin, UUID.randomUUID()))).isEqualTo(404);
		assertThat(status(listFolders(host, admin, UUID.randomUUID()))).isEqualTo(404);

		// Name rules: trimmed, 1..100, no control characters.
		MvcResult trimmed = createFolder(host, admin, "   Trimmed name  ", null);
		assertThat(json(trimmed).path("data").path("name").asString()).isEqualTo("Trimmed name");
		for (String bad : new String[] { "   ", "", "x".repeat(101), "bad\u0007name", "tab\tname" }) {
			MvcResult refused = createFolder(host, admin, bad, null);
			assertThat(status(refused)).as(bad).isEqualTo(400);
			assertThat(firstFieldError(refused)).isEqualTo("name");
		}
		assertThat(status(createFolder(host, admin, null, null))).isEqualTo(400);
		assertThat(status(createFolder(host, admin, "y".repeat(100), null))).isEqualTo(201);

		// Case-insensitive sibling uniqueness, at root and within a parent.
		MvcResult dupRoot = createFolder(host, admin, "  SCIENCE ", null);
		assertThat(status(dupRoot)).isEqualTo(409);
		assertThat(code(dupRoot)).isEqualTo("MEDIA_FOLDER_NAME_TAKEN");
		assertThat(code(createFolder(host, admin, "physics", science))).isEqualTo("MEDIA_FOLDER_NAME_TAKEN");
		assertThat(status(createFolder(host, admin, "Physics", null))).as("other parent").isEqualTo(201);
		assertThat(code(renameFolder(host, admin, chem, "PHYSICS", null))).isEqualTo("MEDIA_FOLDER_NAME_TAKEN");

		long version = json(getFolder(host, admin, chem)).path("data").path("version").asLong();
		MvcResult stale = renameFolder(host, admin, chem, "Chemistry", version + 5);
		assertThat(status(stale)).isEqualTo(409);
		assertThat(code(stale)).isEqualTo("MEDIA_FOLDER_STALE");
		MvcResult renamed = renameFolder(host, admin, chem, "Chemistry", version);
		assertThat(status(renamed)).as(rawContent(renamed)).isEqualTo(200);
		assertThat(json(renamed).path("data").path("version").asLong()).isGreaterThan(version);
		assertThat(status(renameFolder(host, admin, chem, "chemistry", null))).as("own case change").isEqualTo(200);

		assertThat(folderAudits(fx.tenant().getId(), "media_folder.created", science)).isEqualTo(1);
		assertThat(folderAudits(fx.tenant().getId(), "media_folder.renamed", chem)).isEqualTo(2);
	}

	// ------------------------------------------------------------------
	// 2. File move keeps identity and lesson attachments.
	// ------------------------------------------------------------------

	@Test
	void fileMoveIntoFolderAndBackKeepsIdUploaderObjectKeyAndLessonAttachment() {
		Fx fx = seed("mf-move");
		String host = fx.host();
		UUID target = folder(host, fx.adminToken(), "Handouts", null);
		UUID file = uploadOk(host, fx.t1Token(), "Week 1", null);
		MvcResult attached = attach(fx, fx.t1Token(), "Week 1 handout", file);
		assertThat(status(attached)).as(rawContent(attached)).isEqualTo(201);
		String objectKey = jdbcTemplate.queryForObject("SELECT object_key FROM media_file WHERE id = ?", String.class,
				file);

		// t1 (uploader) has no rights on the admin's folder; the admin (tenant-wide) moves it.
		assertThat(status(moveFile(host, fx.t1Token(), file, target))).isEqualTo(404);
		MvcResult moved = moveFile(host, fx.adminToken(), file, target);
		assertThat(status(moved)).as(rawContent(moved)).isEqualTo(200);
		assertThat(json(moved).path("data").path("id").asString()).isEqualTo(file.toString());
		assertThat(json(moved).path("data").path("folderId").asString()).isEqualTo(target.toString());
		assertThat(json(moved).path("data").path("uploadedBy").asString()).isEqualTo(fx.t1().getId().toString());
		assertThat(ids(json(listFiles(host, fx.adminToken(), "?folderId=" + target)).path("data").path("content")))
			.containsExactly(file.toString());
		assertThat(ids(json(listFiles(host, fx.adminToken(), "?unfiled=true")).path("data").path("content")))
			.doesNotContain(file.toString());
		assertThat(json(getFolder(host, fx.adminToken(), target)).path("data").path("fileCount").asLong()).isEqualTo(1);

		MvcResult back = moveFile(host, fx.adminToken(), file, null);
		assertThat(status(back)).as(rawContent(back)).isEqualTo(200);
		assertThat(json(back).path("data").path("folderId").isNull()).isTrue();

		assertThat(jdbcTemplate.queryForObject("SELECT object_key FROM media_file WHERE id = ?", String.class, file))
			.isEqualTo(objectKey);
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND uploaded_by = ? AND folder_id IS NULL",
				file, fx.t1().getId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM material WHERE media_file_id = ? AND removed_at IS NULL", file))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'media_file.moved' "
				+ "AND target_id = ?", fx.tenant().getId(), file)).isEqualTo(2);

		// Upload straight into a folder.
		UUID direct = uploadOk(host, fx.adminToken(), "Direct", target);
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND folder_id = ?", direct, target))
			.isEqualTo(1);
		assertThat(status(upload(host, fx.adminToken(), pdfFile("x.pdf"), "Nowhere", UUID.randomUUID())))
			.isEqualTo(404);
	}

	// ------------------------------------------------------------------
	// 3. Cycles and empty-only delete.
	// ------------------------------------------------------------------

	@Test
	void moveRejectsCyclesAndDeleteRequiresAnEmptyFolderIncludingHiddenContents() {
		Fx fx = seed("mf-cycle");
		String host = fx.host();
		String admin = fx.adminToken();
		UUID a = folder(host, admin, "A", null);
		UUID b = folder(host, admin, "B", a);
		UUID c = folder(host, admin, "C", b);

		MvcResult self = moveFolder(host, admin, a, a);
		assertThat(status(self)).isEqualTo(409);
		assertThat(code(self)).isEqualTo("MEDIA_FOLDER_INVALID_MOVE");
		assertThat(code(moveFolder(host, admin, a, c))).isEqualTo("MEDIA_FOLDER_INVALID_MOVE");
		assertThat(code(moveFolder(host, admin, a, b))).isEqualTo("MEDIA_FOLDER_INVALID_MOVE");
		assertThat(status(moveFolder(host, admin, c, null))).isEqualTo(200);
		assertThat(parentOf(c)).isNull();
		assertThat(status(moveFolder(host, admin, c, a))).isEqualTo(200);
		assertThat(parentOf(c)).isEqualTo(a);
		assertThat(folderAudits(fx.tenant().getId(), "media_folder.moved", c)).isEqualTo(2);

		// t1 owns D; the admin (tenant-wide) adds a child t1 cannot see.
		teachersMayManageAndShare(fx);
		UUID d = folder(host, fx.t1Token(), "D", null);
		UUID hiddenChild = folder(host, admin, "Hidden child", d);
		assertThat(ids(json(listFolders(host, fx.t1Token(), d)).path("data"))).isEmpty();
		MvcResult notEmpty = deleteFolder(host, fx.t1Token(), d, null);
		assertThat(status(notEmpty)).isEqualTo(409);
		assertThat(code(notEmpty)).isEqualTo("MEDIA_FOLDER_NOT_EMPTY");
		assertThat(json(notEmpty).path("error").path("message").asString()).doesNotContainPattern("[0-9]");
		assertThat(status(deleteFolder(host, admin, hiddenChild, null))).isEqualTo(200);

		// A live file inside a hidden subfolder (and then in D itself) blocks; a soft-deleted one does not.
		UUID e = folder(host, admin, "E", d);
		UUID hiddenFile = uploadOk(host, admin, "Hidden file", e);
		assertThat(code(deleteFolder(host, fx.t1Token(), d, null))).isEqualTo("MEDIA_FOLDER_NOT_EMPTY");
		assertThat(code(deleteFolder(host, admin, e, null))).isEqualTo("MEDIA_FOLDER_NOT_EMPTY");
		assertThat(status(call(host, admin, delete("/api/v1/media-files/{id}", hiddenFile)))).isEqualTo(200);
		grantOk(fx, e, "VIEW", fx.t2().getId());
		long version = json(getFolder(host, admin, e)).path("data").path("version").asLong();
		assertThat(code(deleteFolder(host, admin, e, version + 1))).isEqualTo("MEDIA_FOLDER_STALE");
		assertThat(status(deleteFolder(host, admin, e, version))).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM media_folder WHERE id = ?", e)).isZero();
		assertThat(count("SELECT count(*) FROM media_folder_grant WHERE folder_id = ?", e)).isZero();
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND deleted_at IS NOT NULL "
				+ "AND folder_id IS NULL", hiddenFile)).isEqualTo(1);
		assertThat(folderAudits(fx.tenant().getId(), "media_folder.deleted", e)).isEqualTo(1);

		UUID liveFile = uploadOk(host, fx.t1Token(), "Live in D", d);
		assertThat(code(deleteFolder(host, fx.t1Token(), d, null))).isEqualTo("MEDIA_FOLDER_NOT_EMPTY");
		assertThat(status(moveFile(host, fx.t1Token(), liveFile, null))).isEqualTo(200);
		assertThat(status(deleteFolder(host, fx.t1Token(), d, null))).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// 4. Cross-tenant isolation.
	// ------------------------------------------------------------------

	@Test
	void anotherTenantNeverSeesOrTouchesFoldersOrFiledFiles() {
		Fx a = seed("mf-ta");
		Fx b = seed("mf-tb");
		UUID folderA = folder(a.host(), a.adminToken(), "Tenant A private", null);
		UUID childA = folder(a.host(), a.adminToken(), "Child", folderA);
		UUID fileA = uploadOk(a.host(), a.adminToken(), "Secret A", folderA);
		UUID unfiledA = uploadOk(a.host(), a.t2Token(), "Unfiled A", null);
		UUID fileB = uploadOk(b.host(), b.adminToken(), "Own B", null);
		String host = b.host();
		String token = b.adminToken();

		assertThat(status(getFolder(host, token, folderA))).isEqualTo(404);
		assertThat(status(listFolders(host, token, folderA))).isEqualTo(404);
		assertThat(status(listGrants(host, token, folderA))).isEqualTo(404);
		assertThat(status(grant(host, token, folderA, "VIEW", b.t1().getId()))).isEqualTo(404);
		assertThat(status(revoke(host, token, folderA, a.t1().getId()))).isEqualTo(404);
		assertThat(status(call(host, token, get("/api/v1/media-folders/{id}/eligible-members", folderA))))
			.isEqualTo(404);
		assertThat(status(renameFolder(host, token, folderA, "Hijack", null))).isEqualTo(404);
		assertThat(status(moveFolder(host, token, childA, null))).isEqualTo(404);
		assertThat(status(transfer(host, token, folderA, b.t1().getId(), "NONE"))).isEqualTo(404);
		assertThat(status(deleteFolder(host, token, childA, null))).isEqualTo(404);
		assertThat(status(createFolder(host, token, "Into A", folderA))).isEqualTo(404);
		assertThat(status(upload(host, token, pdfFile("b.pdf"), "Into A", folderA))).isEqualTo(404);
		assertThat(status(moveFile(host, token, fileA, null))).isEqualTo(404);
		assertThat(status(moveFile(host, token, fileB, folderA))).isEqualTo(404);
		assertThat(status(openUrl(host, token, fileA))).isEqualTo(404);
		assertThat(status(listFiles(host, token, "?folderId=" + folderA))).isEqualTo(404);
		assertThat(status(uploaders(host, token, "?folderId=" + folderA))).isEqualTo(404);

		assertThat(ids(json(listFolders(host, token, null)).path("data"))).isEmpty();
		String bFiles = rawContent(listFiles(host, token, ""));
		assertThat(bFiles).contains(fileB.toString()).doesNotContain(fileA.toString()).doesNotContain(unfiledA.toString());
		assertThat(ids(json(listFiles(host, token, "?q=Secret")).path("data").path("content"))).isEmpty();
		assertThat(json(summary(host, token)).path("data").path("total").asLong()).isEqualTo(1);
		assertThat(userIds(json(uploaders(host, token, "")).path("data")))
			.containsExactly(b.admin().getId().toString());

		// Tenant A is unchanged.
		assertThat(count("SELECT count(*) FROM media_folder WHERE id IN (?, ?) AND tenant_id = ?", folderA, childA,
				a.tenant().getId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND folder_id = ?", fileA, folderA))
			.isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// 5. Role and grant boundaries.
	// ------------------------------------------------------------------

	@Test
	void teachersSeeOnlyGrantedFoldersAndGrantsNeverGiveManagementRights() {
		Fx fx = seed("mf-roles");
		String host = fx.host();
		UUID tenantId = fx.tenant().getId();
		UUID priv = folder(host, fx.adminToken(), "Private", null);
		UUID secret = uploadOk(host, fx.adminToken(), "Secret notes", priv);
		UUID unfiled = uploadOk(host, fx.adminToken(), "Public notes", null);
		grantOk(fx, priv, "UPLOAD", fx.t2().getId());
		UUID t2File = uploadOk(host, fx.t2Token(), "Teacher two file", priv);
		UUID t1Own = uploadOk(host, fx.t1Token(), "Teacher one file", null);

		// No grant: invisible everywhere (404, never counted).
		String t1 = fx.t1Token();
		assertThat(ids(json(listFolders(host, t1, null)).path("data"))).doesNotContain(priv.toString());
		assertThat(status(getFolder(host, t1, priv))).isEqualTo(404);
		assertThat(status(listFolders(host, t1, priv))).isEqualTo(404);
		List<String> visible = ids(json(listFiles(host, t1, "")).path("data").path("content"));
		assertThat(visible).contains(unfiled.toString(), t1Own.toString())
			.doesNotContain(secret.toString(), t2File.toString());
		assertThat(ids(json(listFiles(host, t1, "?q=secret")).path("data").path("content"))).isEmpty();
		assertThat(json(summary(host, t1)).path("data").path("total").asLong()).isEqualTo(2);
		assertThat(json(summary(host, fx.adminToken())).path("data").path("total").asLong()).isEqualTo(4);
		assertThat(userIds(json(uploaders(host, t1, "")).path("data"))).doesNotContain(fx.t2().getId().toString());
		assertThat(status(uploaders(host, t1, "?folderId=" + priv))).isEqualTo(404);
		assertThat(status(listFiles(host, t1, "?folderId=" + priv))).isEqualTo(404);
		assertThat(status(openUrl(host, t1, secret))).isEqualTo(404);
		assertThat(status(attach(fx, t1, "Leak", secret))).isEqualTo(404);
		assertThat(status(moveFile(host, t1, secret, null))).isEqualTo(404);
		assertThat(status(upload(host, t1, pdfFile("p.pdf"), "Push", priv))).isEqualTo(404);

		// Teacher default: no folder capability.
		assertThat(status(createFolder(host, t1, "Mine", null))).isEqualTo(403);
		assertThat(status(listGrants(host, fx.t2Token(), priv))).isEqualTo(403);

		// VIEW grant: browse + open, attach by id, but no upload / move-in.
		grantOk(fx, priv, "VIEW", fx.t1().getId());
		JsonNode seen = json(listFolders(host, t1, null)).path("data");
		assertThat(ids(seen)).contains(priv.toString());
		JsonNode privView = json(getFolder(host, t1, priv)).path("data");
		assertThat(privView.path("access").asString()).isEqualTo("VIEW");
		assertThat(privView.path("canUpload").asBoolean()).isFalse();
		assertThat(privView.path("canManage").asBoolean()).isFalse();
		assertThat(ids(json(listFiles(host, t1, "?folderId=" + priv)).path("data").path("content")))
			.containsExactlyInAnyOrder(secret.toString(), t2File.toString());
		assertThat(ids(json(listFiles(host, t1, "?folderId=" + priv + "&uploadedBy=" + fx.t2().getId())).path("data")
			.path("content"))).containsExactly(t2File.toString());
		assertThat(ids(json(listFiles(host, t1, "?unfiled=true&uploadedBy=" + fx.t1().getId())).path("data")
			.path("content"))).containsExactly(t1Own.toString());
		assertThat(status(listFiles(host, t1, "?unfiled=true&folderId=" + priv))).isEqualTo(400);
		assertThat(userIds(json(uploaders(host, t1, "?folderId=" + priv)).path("data")))
			.containsExactlyInAnyOrder(fx.admin().getId().toString(), fx.t2().getId().toString());
		assertThat(status(openUrl(host, t1, secret))).isEqualTo(200);
		assertThat(status(upload(host, t1, pdfFile("p.pdf"), "Push", priv))).isEqualTo(403);
		assertThat(status(moveFile(host, t1, t1Own, priv))).isEqualTo(403);
		MvcResult attachedOk = attach(fx, t1, "Shared handout", secret);
		assertThat(status(attachedOk)).as(rawContent(attachedOk)).isEqualTo(201);

		// UPLOAD grant (teachers now hold Manage + Share): upload / move-in, but no folder management.
		teachersMayManageAndShare(fx);
		grantOk(fx, priv, "UPLOAD", fx.t1().getId());
		assertThat(status(upload(host, t1, pdfFile("p.pdf"), "Pushed", priv))).isEqualTo(201);
		assertThat(status(moveFile(host, t1, t1Own, priv))).isEqualTo(200);
		assertThat(status(renameFolder(host, t1, priv, "Renamed", null))).isEqualTo(403);
		assertThat(status(moveFolder(host, t1, priv, null))).isEqualTo(403);
		assertThat(status(deleteFolder(host, t1, priv, null))).isEqualTo(403);
		assertThat(status(listGrants(host, t1, priv))).isEqualTo(403);
		assertThat(status(grant(host, t1, priv, "VIEW", fx.t3().getId()))).isEqualTo(403);
		assertThat(status(transfer(host, t1, priv, fx.t1().getId(), "NONE"))).isEqualTo(403);
		assertThat(status(createFolder(host, t1, "Sub", priv))).isEqualTo(403);
		assertThat(status(call(host, t1, patch("/api/v1/media-files/{id}", t2File).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Mine now\"}")))).as("another uploader's file").isEqualTo(404);
		assertThat(status(moveFile(host, t1, t2File, null))).as("another uploader's file").isEqualTo(404);

		// Revoking stops new open-URL issuance at once.
		MvcResult revoked = revoke(host, fx.adminToken(), priv, fx.t1().getId());
		assertThat(status(revoked)).isEqualTo(200);
		assertThat(userIds(json(revoked).path("data"))).containsExactly(fx.t2().getId().toString());
		assertThat(status(openUrl(host, t1, secret))).isEqualTo(404);
		assertThat(status(getFolder(host, t1, priv))).isEqualTo(404);
		assertThat(folderAudits(tenantId, "media_folder.shared", priv)).isEqualTo(3);
		assertThat(folderAudits(tenantId, "media_folder.unshared", priv)).isEqualTo(1);

		// A tenant grant of Manage lets a teacher create (and see) only their own folders.
		String t3 = fx.t3Token();
		MvcResult own = createFolder(host, t3, "Teacher three", null);
		assertThat(status(own)).as(rawContent(own)).isEqualTo(201);
		assertThat(json(own).path("data").path("access").asString()).isEqualTo("OWNER");
		assertThat(json(own).path("data").path("ownerUserId").asString()).isEqualTo(fx.t3().getId().toString());
		assertThat(ids(json(listFolders(host, t3, null)).path("data")))
			.containsExactly(json(own).path("data").path("id").asString());
		assertThat(status(renameFolder(host, t3, priv, "Hijack", null))).isEqualTo(404);
		assertThat(status(createFolder(host, t3, "Sub", priv))).isEqualTo(404);

		// Content Manager: library access, but no folder capability and no tenant-wide visibility.
		String cm = fx.cmToken();
		assertThat(status(createFolder(host, cm, "CM", null))).isEqualTo(403);
		assertThat(ids(json(listFolders(host, cm, null)).path("data"))).isEmpty();
		assertThat(status(getFolder(host, cm, priv))).isEqualTo(404);
		assertThat(ids(json(listFiles(host, cm, "")).path("data").path("content"))).doesNotContain(secret.toString());

		// Upload grant without Add Media: canUpload false and the upload is refused.
		jdbcTemplate.update("INSERT INTO media_folder_grant (id, tenant_id, folder_id, grantee_user_id, access_level, "
				+ "granted_by, granted_at) VALUES (?, ?, ?, ?, 'UPLOAD', ?, now())", UUID.randomUUID(), tenantId, priv,
				fx.cm().getId(), fx.admin().getId());
		assertThat(json(getFolder(host, cm, priv)).path("data").path("canUpload").asBoolean()).isTrue();
		setRoleCapability(tenantId, "CONTENT_MANAGER", "MEDIA_ADD", false);
		assertThat(json(getFolder(host, cm, priv)).path("data").path("canUpload").asBoolean()).isFalse();
		assertThat(status(upload(host, cm, pdfFile("cm.pdf"), "CM push", priv))).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// 6. Directly shared nested folder.
	// ------------------------------------------------------------------

	@Test
	void directlySharedNestedFolderAppearsAtRootWithoutItsHiddenPath() {
		Fx fx = seed("mf-nested");
		String host = fx.host();
		UUID a = folder(host, fx.adminToken(), "Ancestor", null);
		UUID b = folder(host, fx.adminToken(), "Branch", a);
		UUID c = folder(host, fx.adminToken(), "Leaf", b);
		grantOk(fx, c, "VIEW", fx.t1().getId());
		String t1 = fx.t1Token();

		JsonNode root = json(listFolders(host, t1, null)).path("data");
		assertThat(ids(root)).containsExactly(c.toString());
		assertThat(root.path(0).path("parentId").isNull()).isTrue();
		JsonNode leaf = json(getFolder(host, t1, c)).path("data");
		assertThat(leaf.path("parentId").isNull()).isTrue();
		assertThat(leaf.path("breadcrumb").size()).isZero();
		assertThat(rawContent(getFolder(host, t1, c))).doesNotContain(a.toString()).doesNotContain(b.toString())
			.doesNotContain("Ancestor").doesNotContain("Branch");

		// Sharing the middle folder too: the chain stops at the first hidden ancestor.
		grantOk(fx, b, "VIEW", fx.t1().getId());
		assertThat(ids(json(listFolders(host, t1, null)).path("data"))).containsExactly(b.toString());
		assertThat(ids(json(listFolders(host, t1, b)).path("data"))).containsExactly(c.toString());
		JsonNode leafAgain = json(getFolder(host, t1, c)).path("data");
		assertThat(leafAgain.path("parentId").asString()).isEqualTo(b.toString());
		assertThat(ids(leafAgain.path("breadcrumb"))).containsExactly(b.toString());
		JsonNode branch = json(getFolder(host, t1, b)).path("data");
		assertThat(branch.path("parentId").isNull()).isTrue();
		assertThat(branch.path("breadcrumb").size()).isZero();
		assertThat(status(getFolder(host, t1, a))).isEqualTo(404);
	}

	// ------------------------------------------------------------------
	// 7. Ownership transfer.
	// ------------------------------------------------------------------

	@Test
	void transferMovesOnlyTheFormerOwnersFoldersForEveryRetention() {
		Fx fx = seed("mf-transfer");
		String host = fx.host();
		UUID tenantId = fx.tenant().getId();
		teachersMayManageAndShare(fx);
		String t1 = fx.t1Token();
		for (String retention : new String[] { "NONE", "VIEW", "UPLOAD" }) {
			// F (t1) > S1 (t1), S2 (admin) > S3 (t1 via an admin transfer).
			UUID f = folder(host, t1, "Root " + retention, null);
			UUID s1 = folder(host, t1, "Sub one", f);
			UUID s2 = folder(host, fx.adminToken(), "Admin sub", f);
			UUID s3 = folder(host, fx.adminToken(), "Deep", s2);
			MvcResult adminTransfer = transfer(host, fx.adminToken(), s3, fx.t1().getId(), "NONE");
			assertThat(status(adminTransfer)).as(rawContent(adminTransfer)).isEqualTo(200);
			assertThat(ownerOf(s3)).isEqualTo(fx.t1().getId());

			// Two uploaders; an unrelated grant (t2 UPLOAD on F); the new owner's prior grant (t3 VIEW on S1).
			assertThat(status(grant(host, t1, f, "UPLOAD", fx.t2().getId()))).isEqualTo(200);
			assertThat(status(grant(host, t1, s1, "VIEW", fx.t3().getId()))).isEqualTo(200);
			UUID file1 = uploadOk(host, t1, "By one " + retention, f);
			UUID file2 = uploadOk(host, fx.t2Token(), "By two " + retention, f);
			UUID file3 = uploadOk(host, t1, "Deep file " + retention, s3);

			// Grantees and invalid targets cannot transfer.
			assertThat(status(transfer(host, fx.t2Token(), f, fx.t3().getId(), retention))).isEqualTo(403);
			assertThat(status(transfer(host, t1, f, fx.t1().getId(), retention))).isEqualTo(400);
			assertThat(status(transfer(host, t1, f, fx.admin().getId(), retention))).isEqualTo(400);
			MvcResult members = call(host, t1, get("/api/v1/media-folders/{id}/eligible-members", f));
			assertThat(userIds(json(members).path("data"))).containsExactlyInAnyOrder(fx.t2().getId().toString(),
					fx.t3().getId().toString());
			assertThat(rawContent(members)).doesNotContain("@example.test");

			MvcResult done = transfer(host, t1, f, fx.t3().getId(), retention);
			assertThat(status(done)).as(rawContent(done)).isEqualTo(200);
			assertThat(json(done).path("data").path("ownerUserId").asString()).isEqualTo(fx.t3().getId().toString());

			assertThat(ownerOf(f)).isEqualTo(fx.t3().getId());
			assertThat(ownerOf(s1)).isEqualTo(fx.t3().getId());
			assertThat(ownerOf(s3)).isEqualTo(fx.t3().getId());
			assertThat(ownerOf(s2)).as("mixed owner keeps its owner").isEqualTo(fx.admin().getId());
			assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND uploaded_by = ? AND folder_id = ?",
					file1, fx.t1().getId(), f)).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND uploaded_by = ? AND folder_id = ?",
					file2, fx.t2().getId(), f)).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM media_file WHERE id = ? AND uploaded_by = ? AND folder_id = ?",
					file3, fx.t1().getId(), s3)).isEqualTo(1);
			assertThat(grantLevel(f, fx.t2().getId())).as("unrelated grant").isEqualTo("UPLOAD");
			assertThat(grantLevel(s1, fx.t3().getId())).as("new owner's prior grant").isNull();
			String expected = "NONE".equals(retention) ? null : retention;
			assertThat(grantLevel(f, fx.t1().getId())).isEqualTo(expected);
			assertThat(grantLevel(s1, fx.t1().getId())).isEqualTo(expected);
			assertThat(grantLevel(s3, fx.t1().getId())).isEqualTo(expected);
			assertThat(grantLevel(s2, fx.t1().getId())).as("not transferred").isNull();

			// Former owner's access follows the retention.
			int expectedStatus = "NONE".equals(retention) ? 404 : 200;
			assertThat(status(getFolder(host, t1, f))).isEqualTo(expectedStatus);
			assertThat(status(openUrl(host, t1, file2))).isEqualTo(expectedStatus);
			assertThat(status(getFolder(host, fx.t3Token(), s1))).isEqualTo(200);

			assertThat(folderAudits(tenantId, "media_folder.ownership_transferred", f)).isEqualTo(1);
			String metadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? "
					+ "AND action = 'media_folder.ownership_transferred' AND target_id = ?", String.class, tenantId, f);
			assertThat(metadata).contains(f.toString(), s1.toString(), s3.toString()).doesNotContain(s2.toString())
				.contains(retention);
		}
	}

	// ------------------------------------------------------------------
	// 8. Concurrent cycle race.
	// ------------------------------------------------------------------

	@Test
	void concurrentCrossMovesNeverCreateACycle() throws Exception {
		Fx fx = seed("mf-race");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 3; round++) {
				UUID x = folder(fx.host(), fx.adminToken(), "X" + round, null);
				UUID y = folder(fx.host(), fx.adminToken(), "Y" + round, null);
				CountDownLatch start = new CountDownLatch(1);
				Future<MvcResult> first = pool.submit(() -> {
					start.await();
					return moveFolder(fx.host(), fx.adminToken(), x, y);
				});
				Future<MvcResult> second = pool.submit(() -> {
					start.await();
					return moveFolder(fx.host(), fx.adminToken(), y, x);
				});
				start.countDown();
				MvcResult r1 = first.get(60, TimeUnit.SECONDS);
				MvcResult r2 = second.get(60, TimeUnit.SECONDS);
				List<Integer> statuses = new ArrayList<>(List.of(status(r1), status(r2)));
				statuses.sort(Integer::compareTo);
				assertThat(statuses).as(rawContent(r1) + " / " + rawContent(r2)).containsExactly(200, 409);
				MvcResult loser = status(r1) == 409 ? r1 : r2;
				assertThat(code(loser)).isEqualTo("MEDIA_FOLDER_INVALID_MOVE");
				UUID px = parentOf(x);
				UUID py = parentOf(y);
				assertThat(px == null ^ py == null).as("exactly one moved, no cycle").isTrue();
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

}
