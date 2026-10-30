package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice A - module question bank, exam images and CSV import over real
 * HTTP + Postgres (Testcontainers). Covers the contract rules (D1, marks, rich-content
 * sanitizing, protected images), soft delete, copy, import atomicity/idempotency/history,
 * and tenant / teacher / student isolation on every endpoint.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class ModuleQuestionBankIntegrationTest extends ExamManagementTestSupport {

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H',
			'D', 'R' };

	private record Bank(ExamFixture fx, UUID courseId, UUID moduleId) {

		String host() {
			return fx.host();
		}

	}

	private Bank seedBank(String prefix) {
		ExamFixture fx = seedExamFixture(prefix);
		UUID moduleId = createModuleOrFail(fx.host(), fx.adminToken(), fx.course().id(), "Module 1", 1).id();
		return new Bank(fx, fx.course().id(), moduleId);
	}

	// ------------------------------------------------------------------
	// CRUD, ordering, soft delete.
	// ------------------------------------------------------------------

	@Test
	void crudRoundTripKeepsMarksExplanationAndOptionOrderAndSoftDeletes() {
		Bank bank = seedBank("qb2-crud");
		String token = bank.fx().teacherToken();

		MvcResult created = createQuestion(bank, token, question("<p>Capital of France?</p>", 2.5,
				"<p>Paris is the capital.</p>", opt("<p>Berlin</p>", false), opt("<p>Paris</p>", true),
				opt("<p>Rome</p>", false)));
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		JsonNode q1 = data(created);
		assertThat(q1.path("courseId").asString()).isEqualTo(bank.courseId().toString());
		assertThat(q1.path("moduleId").asString()).isEqualTo(bank.moduleId().toString());
		assertThat(q1.path("sequence").asInt()).isEqualTo(1);
		assertThat(q1.path("marks").decimalValue()).isEqualByComparingTo("2.5");
		assertThat(q1.path("explanationHtml").asString()).isEqualTo("<p>Paris is the capital.</p>");
		assertThat(q1.path("importId").isNull()).isTrue();
		assertThat(texts(q1.path("options"), "html")).containsExactly("<p>Berlin</p>", "<p>Paris</p>", "<p>Rome</p>");
		assertThat(texts(q1.path("options"), "correct")).containsExactly("false", "true", "false");
		assertThat(texts(q1.path("options"), "sequence")).containsExactly("1", "2", "3");

		MvcResult second = createQuestion(bank, token,
				question("<p>2 + 2?</p>", null, "<p></p>", opt("<p>4</p>", true), opt("<p>5</p>", false)));
		assertThat(status(second)).isEqualTo(201);
		JsonNode q2 = data(second);
		assertThat(q2.path("sequence").asInt()).isEqualTo(2);
		assertThat(q2.path("marks").decimalValue()).isEqualByComparingTo("1");
		assertThat(q2.path("explanationHtml").isNull()).as("empty explanation is stored as null").isTrue();

		JsonNode listed = data(call(bank.host(), token, get("/api/v1/courses/{c}/modules/{m}/questions?page=0&size=50",
				bank.courseId(), bank.moduleId())));
		assertThat(texts(listed.path("content"), "id")).containsExactly(q1.path("id").asString(),
				q2.path("id").asString());

		// Update: keep Berlin (renamed) and Paris by id, drop Rome, add a new answer at the front.
		UUID q1Id = UUID.fromString(q1.path("id").asString());
		String berlinId = q1.path("options").get(0).path("id").asString();
		String parisId = q1.path("options").get(1).path("id").asString();
		MvcResult updated = call(bank.host(), token, put("/api/v1/exam-questions/{id}", q1Id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(question("<p>Capital of <strong>France</strong>?</p>", 3, null,
					opt("<p>Madrid</p>", false), optWithId(parisId, "<p>Paris</p>", true),
					optWithId(berlinId, "<p>Berlin (DE)</p>", false)))));
		assertThat(status(updated)).as(rawContent(updated)).isEqualTo(200);
		JsonNode u = data(updated);
		assertThat(u.path("marks").decimalValue()).isEqualByComparingTo("3");
		assertThat(u.path("explanationHtml").isNull()).isTrue();
		assertThat(texts(u.path("options"), "html")).containsExactly("<p>Madrid</p>", "<p>Paris</p>",
				"<p>Berlin (DE)</p>");
		assertThat(u.path("options").get(1).path("id").asString()).isEqualTo(parisId);
		assertThat(u.path("options").get(2).path("id").asString()).isEqualTo(berlinId);
		assertThat(countRows("SELECT count(*) FROM exam_question_option WHERE question_id = ?", q1Id)).isEqualTo(3);

		JsonNode fetched = data(call(bank.host(), token, get("/api/v1/exam-questions/{id}", q1Id)));
		assertThat(fetched.path("bodyHtml").asString()).isEqualTo("<p>Capital of <strong>France</strong>?</p>");

		// Soft delete: gone from the bank, row kept, remaining question re-sequenced, audited.
		MvcResult deleted = call(bank.host(), token, delete("/api/v1/exam-questions/{id}", q1Id));
		assertThat(status(deleted)).isEqualTo(204);
		assertThat(status(call(bank.host(), token, get("/api/v1/exam-questions/{id}", q1Id)))).isEqualTo(404);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE id = ? AND deleted_at IS NOT NULL", q1Id))
			.isEqualTo(1);
		JsonNode afterDelete = data(call(bank.host(), token,
				get("/api/v1/courses/{c}/modules/{m}/questions", bank.courseId(), bank.moduleId())));
		assertThat(afterDelete.path("totalElements").asInt()).isEqualTo(1);
		assertThat(afterDelete.path("content").get(0).path("sequence").asInt()).isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE action = 'exam_question.deleted' AND target_id = ?",
				q1Id)).isEqualTo(1);
		assertThat(status(call(bank.host(), token, put("/api/v1/exam-questions/{id}", q1Id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(
					question("<p>x</p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false))))))).isEqualTo(404);
	}

	@Test
	void softDeletingAQuestionUsedByAnExamKeepsTheExamLinkIntact() {
		Bank bank = seedBank("qb2-linked");
		String token = bank.fx().teacherToken();
		UUID questionId = createQuestionOk(bank, token, "<p>Linked?</p>");
		var exam = createAndPrepareDraftExamOrFail(bank.host(), token, bank.courseId(), "Quiz",
				Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 30, List.of(questionId));

		assertThat(status(call(bank.host(), token, delete("/api/v1/exam-questions/{id}", questionId)))).isEqualTo(204);

		assertThat(countRows("SELECT count(*) FROM exam_question_link WHERE exam_id = ? AND question_id = ?", exam.id(),
				questionId)).isEqualTo(1);
		// A deleted question can no longer be put into an exam.
		var relink = updateDraftExam(bank.host(), token, exam.id(), "Quiz", Instant.now().plusSeconds(3600),
				Instant.now().plusSeconds(7200), 30, List.of(questionId));
		assertThat(relink.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void legacyCourseLevelEndpointsNeverExposeOrEditModuleQuestions() {
		Bank bank = seedBank("qb2-legacy");
		String token = bank.fx().teacherToken();
		UUID moduleQuestion = createQuestionOk(bank, token, "<p>Module question</p>");
		var legacy = createMcqQuestionOrFail(bank.host(), token, bank.courseId(), "Legacy question", "a", "b");

		var legacyList = listQuestions(bank.host(), token, bank.courseId());
		assertThat(legacyList.getBody().data().content()).extracting(r -> r.id()).containsExactly(legacy.id());
		var legacyUpdate = updateQuestion(bank.host(), token, moduleQuestion, "<script>x</script>",
				List.of(new com.lms.exammanagement.web.dto.ExamQuestionOptionRequest("a", true),
						new com.lms.exammanagement.web.dto.ExamQuestionOptionRequest("b", false)));
		assertThat(legacyUpdate.getStatusCode().value()).isEqualTo(404);
		assertThat(status(call(bank.host(), token, get("/api/v1/exam-questions/{id}", legacy.id())))).isEqualTo(404);
	}

	// ------------------------------------------------------------------
	// Validation (D1, marks, content).
	// ------------------------------------------------------------------

	@Test
	void d1AndMarksRulesAreEnforcedAndNothingIsStored() {
		Bank bank = seedBank("qb2-d1");
		String token = bank.fx().teacherToken();

		List<Map<String, Object>> invalid = List.of(
				question("<p>q</p>", 1, null, opt("<p>a</p>", false), opt("<p>b</p>", false)),
				question("<p>q</p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", true)),
				question("<p>q</p>", 1, null, opt("<p>a</p>", true)),
				question("<p>q</p>", 0, null, opt("<p>a</p>", true), opt("<p>b</p>", false)),
				question("<p>q</p>", 100.5, null, opt("<p>a</p>", true), opt("<p>b</p>", false)),
				question("<p>q</p>", 1.234, null, opt("<p>a</p>", true), opt("<p>b</p>", false)),
				question("<p>q</p>", 1, null, opt("<p>a</p>", true), opt("<p> </p>", false)),
				question("<p></p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false)),
				question("<p>&nbsp;</p><p><br></p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false)));
		for (Map<String, Object> body : invalid) {
			MvcResult result = createQuestion(bank, token, body);
			assertThat(status(result)).as(body + " -> " + rawContent(result)).isEqualTo(400);
		}
		List<Map<String, Object>> elevenOptions = new ArrayList<>();
		elevenOptions.add(opt("<p>right</p>", true));
		for (int i = 0; i < 10; i++) {
			elevenOptions.add(opt("<p>w" + i + "</p>", false));
		}
		Map<String, Object> tooMany = question("<p>q</p>", 1, null);
		tooMany.put("options", elevenOptions);
		assertThat(status(createQuestion(bank, token, tooMany))).isEqualTo(400);

		assertThat(countRows("SELECT count(*) FROM exam_question WHERE course_id = ?", bank.courseId())).isZero();
	}

	@Test
	void imageOnlyAndMathOnlyContentIsAcceptedAndImagesGetASignedSourceOnRead() {
		Bank bank = seedBank("qb2-media");
		String token = bank.fx().teacherToken();
		UUID mediaId = uploadImage(bank.host(), token);

		MvcResult created = createQuestion(bank, token,
				question("<p><img data-media-id=\"" + mediaId + "\" src=\"https://evil/x.png\"></p>", 1, null,
						opt("<span data-math=\"inline\" data-latex=\"x^2\"></span>", true),
						opt("<div data-math=\"block\" data-latex=\"\\sqrt{2}\"></div>", false)));

		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		JsonNode q = data(created);
		String body = q.path("bodyHtml").asString();
		assertThat(body).contains("data-media-id=\"" + mediaId + "\"").contains("src=\"").doesNotContain("evil");
		String stored = jdbcTemplate.queryForObject("SELECT body FROM exam_question WHERE id = ?::uuid", String.class,
				q.path("id").asString());
		assertThat(stored).as("src is never persisted").doesNotContain("src");
		assertThat(q.path("options").get(0).path("html").asString())
			.isEqualTo("<span data-math=\"inline\" data-latex=\"x^2\"></span>");
	}

	@Test
	void scriptsEventHandlersAndJavascriptUrlsAreStripped() {
		Bank bank = seedBank("qb2-xss");
		String token = bank.fx().teacherToken();

		MvcResult created = createQuestion(bank, token, question(
				"<p onclick=\"steal()\" style=\"x\">Safe<script>alert(1)</script>"
						+ "<a href=\"javascript:alert(1)\">link</a><img src=x onerror=\"alert(1)\"></p>",
				1, "<p>ok<iframe src=\"javascript:alert(1)\"></iframe></p>",
				opt("<p>a<svg onload=\"alert(1)\"></svg></p>", true), opt("<p>b</p>", false)));

		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		String raw = rawContent(created);
		assertThat(raw).doesNotContain("script", "onclick", "onerror", "onload", "javascript:", "href", "iframe",
				"svg", "style=");
		JsonNode q = data(created);
		assertThat(q.path("bodyHtml").asString()).isEqualTo("<p>Safelink</p>");
		assertThat(q.path("explanationHtml").asString()).isEqualTo("<p>ok</p>");
	}

	@Test
	void anImageOfAnotherTenantOrAnUnknownImageIsRejected() {
		Bank bank = seedBank("qb2-img-a");
		Bank other = seedBank("qb2-img-b");
		UUID foreignMedia = uploadImage(other.host(), other.fx().teacherToken());

		for (UUID mediaId : List.of(foreignMedia, UUID.randomUUID())) {
			MvcResult result = createQuestion(bank, bank.fx().teacherToken(), question(
					"<p>see <img data-media-id=\"" + mediaId + "\"></p>", 1, null, opt("<p>a</p>", true),
					opt("<p>b</p>", false)));
			assertThat(status(result)).as(rawContent(result)).isEqualTo(400);
		}
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE course_id = ?", bank.courseId())).isZero();
	}

	@Test
	void examMediaUploadAcceptsImagesOnlyAndKeepsThemOutOfTheMediaLibrary() {
		Bank bank = seedBank("qb2-upload");
		String token = bank.fx().teacherToken();

		MvcResult ok = callMultipart(bank.host(), token, multipart("/api/v1/exam-media").file(new MockMultipartFile("file", "diagram.png", "image/png", PNG)));
		assertThat(status(ok)).as(rawContent(ok)).isEqualTo(201);
		assertThat(data(ok).path("url").asString()).isNotBlank();
		assertThat(jdbcTemplate.queryForObject("SELECT usage_area FROM media_file WHERE id = ?::uuid", String.class,
				data(ok).path("mediaId").asString())).isEqualTo("EXAM_QUESTION");

		MvcResult text = callMultipart(bank.host(), token, multipart("/api/v1/exam-media")
			.file(new MockMultipartFile("file", "fake.png", "image/png", "just text".getBytes(StandardCharsets.UTF_8))));
		assertThat(status(text)).isEqualTo(415);

		JsonNode library = data(call(bank.host(), bank.fx().adminToken(), get("/api/v1/media-files")));
		assertThat(library.path("totalElements").asInt()).isZero();
	}

	// ------------------------------------------------------------------
	// Copy and counts.
	// ------------------------------------------------------------------

	@Test
	void aTeacherOrAssistantCannotEmbedAnotherUploadersImageButExistingReferencesAndStaffStillWork() {
		Bank bank = seedBank("qb2-img-owner");
		String teacherA = bank.fx().teacherToken();
		UUID imageA = uploadImage(bank.host(), teacherA);
		String imgA = "<p>see <img data-media-id=\"" + imageA + "\"></p>";
		MvcResult own = createQuestion(bank, teacherA,
				question(imgA, 1, null, opt("<p>right</p>", true), opt("<p>wrong</p>", false)));
		assertThat(status(own)).as(rawContent(own)).isEqualTo(201);
		UUID questionA = UUID.fromString(data(own).path("id").asString());

		// Teacher B, in their own course of the same tenant, can't embed teacher A's image id.
		TenantUser teacher2 = seedTenantUser(bank.fx().tenant().getId(), "teacher2@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String teacherB = loginAndGetToken(bank.host(), "teacher2@example.test");
		var courseB = createCourseOrFail(bank.host(), bank.fx().adminToken(),
				newCourseRequest(uniqueSlug("qb2-img-b"), teacher2.getId(), CourseStatus.PUBLIC));
		UUID moduleB = createModuleOrFail(bank.host(), bank.fx().adminToken(), courseB.id(), "M", 1).id();
		Bank bankB = new Bank(bank.fx(), courseB.id(), moduleB);
		for (Map<String, Object> body : List.of(
				question(imgA, 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false)),
				question("<p>q</p>", 1, imgA, opt("<p>a</p>", true), opt("<p>b</p>", false)),
				question("<p>q</p>", 1, null, opt(imgA, true), opt("<p>b</p>", false)))) {
			MvcResult foreign = createQuestion(bankB, teacherB, body);
			assertThat(status(foreign)).as(rawContent(foreign)).isEqualTo(400);
		}
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE course_id = ?", courseB.id())).isZero();
		// Their own upload works.
		UUID imageB = uploadImage(bank.host(), teacherB);
		MvcResult ownB = createQuestion(bankB, teacherB, question("<p><img data-media-id=\"" + imageB + "\"></p>", 1,
				null, opt("<p>a</p>", true), opt("<p>b</p>", false)));
		assertThat(status(ownB)).as(rawContent(ownB)).isEqualTo(201);

		// A teacher assistant (tenant-wide authoring) can't add A's image to a new question ...
		seedTenantUser(bank.fx().tenant().getId(), "ta-img@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(bank.host(), "ta-img@example.test");
		MvcResult taForeign = createQuestion(bank, ta,
				question(imgA, 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false)));
		assertThat(status(taForeign)).as(rawContent(taForeign)).isEqualTo(400);
		// ... but can edit A's question that already contains it.
		MvcResult taEdit = call(bank.host(), ta, put("/api/v1/exam-questions/{q}", questionA)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(question(imgA + "<p>edited by TA</p>", 2, null,
					opt("<p>right</p>", true), opt("<p>wrong</p>", false)))));
		assertThat(status(taEdit)).as(rawContent(taEdit)).isEqualTo(200);
		assertThat(data(taEdit).path("bodyHtml").asString()).contains(imageA.toString()).contains("edited by TA");

		// Staff with EXAMS authoring (tenant admin) keep tenant-wide image access.
		MvcResult admin = createQuestion(bank, bank.fx().adminToken(),
				question(imgA + "<p>admin</p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false)));
		assertThat(status(admin)).as(rawContent(admin)).isEqualTo(201);

		// Copy keeps the existing references (no re-validation of the uploader).
		UUID ownSecondModule = createModuleOrFail(bank.host(), bank.fx().adminToken(), bank.courseId(), "M2", 2).id();
		assertThat(status(copy(bank, teacherA, bank.courseId(), ownSecondModule))).isEqualTo(200);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id = ? AND body LIKE ?", ownSecondModule,
				"%" + imageA + "%")).isEqualTo(2);
	}

	@Test
	void copyDuplicatesEveryQuestionAsIndependentRowsAndCountsReflectIt() {
		Bank bank = seedBank("qb2-copy");
		String token = bank.fx().teacherToken();
		UUID first = createQuestionOk(bank, token, "<p>One</p>");
		createQuestion(bank, token,
				question("<p>Two</p>", 2.5, "<p>why</p>", opt("<p>x</p>", false), opt("<p>y</p>", true)));
		UUID moduleB = createModuleOrFail(bank.host(), bank.fx().adminToken(), bank.courseId(), "Module 2", 2).id();
		createQuestionOk(new Bank(bank.fx(), bank.courseId(), moduleB), token, "<p>Existing in B</p>");

		MvcResult copied = copy(bank, token, bank.courseId(), moduleB);
		assertThat(status(copied)).as(rawContent(copied)).isEqualTo(200);
		assertThat(data(copied).path("copied").asInt()).isEqualTo(2);

		JsonNode target = data(call(bank.host(), token,
				get("/api/v1/courses/{c}/modules/{m}/questions", bank.courseId(), moduleB)));
		assertThat(texts(target.path("content"), "bodyHtml")).containsExactly("<p>Existing in B</p>", "<p>One</p>",
				"<p>Two</p>");
		assertThat(texts(target.path("content"), "sequence")).containsExactly("1", "2", "3");
		JsonNode copiedTwo = target.path("content").get(2);
		assertThat(copiedTwo.path("explanationHtml").asString()).isEqualTo("<p>why</p>");
		assertThat(texts(copiedTwo.path("options"), "correct")).containsExactly("false", "true");
		assertThat(target.path("content").get(1).path("id").asString()).isNotEqualTo(first.toString());

		// Independent: deleting the source leaves the copy.
		call(bank.host(), token, delete("/api/v1/exam-questions/{id}", first));
		JsonNode counts = data(call(bank.host(), token,
				get("/api/v1/courses/{c}/modules/question-counts", bank.courseId())));
		assertThat(counts.size()).isEqualTo(2);
		assertThat(counts.get(0).path("moduleId").asString()).isEqualTo(bank.moduleId().toString());
		assertThat(counts.get(0).path("moduleName").asString()).isEqualTo("Module 1");
		assertThat(counts.get(0).path("questionCount").asInt()).isEqualTo(1);
		assertThat(counts.get(0).path("totalMarks").decimalValue()).isEqualByComparingTo("2.5");
		assertThat(counts.get(1).path("questionCount").asInt()).isEqualTo(3);
		assertThat(counts.get(1).path("totalMarks").decimalValue()).isEqualByComparingTo("4.5");
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE action = 'exam_question.copied' AND target_id = ?",
				moduleB)).isEqualTo(1);

		assertThat(status(copy(bank, token, bank.courseId(), bank.moduleId()))).as("same module").isEqualTo(400);
	}

	@Test
	void copyTargetsMustBeAuthorizedAndBelongToTheirCourse() {
		Bank bank = seedBank("qb2-copy-authz");
		Bank foreign = seedBank("qb2-copy-foreign");
		String token = bank.fx().teacherToken();
		createQuestionOk(bank, token, "<p>One</p>");
		TenantUser teacher2 = seedTenantUser(bank.fx().tenant().getId(), "teacher2@example.test", RAW_PASSWORD,
				Role.TEACHER);
		var otherCourse = createCourseOrFail(bank.host(), bank.fx().adminToken(),
				newCourseRequest(uniqueSlug("qb2-other"), teacher2.getId(), CourseStatus.PUBLIC));
		UUID otherModule = createModuleOrFail(bank.host(), bank.fx().adminToken(), otherCourse.id(), "M", 1).id();
		UUID ownSecondModule = createModuleOrFail(bank.host(), bank.fx().adminToken(), bank.courseId(), "M2", 2).id();

		assertThat(status(copy(bank, token, foreign.courseId(), foreign.moduleId()))).as("cross-tenant target")
			.isEqualTo(404);
		assertThat(status(copy(bank, token, bank.courseId(), foreign.moduleId()))).as("foreign module").isEqualTo(404);
		assertThat(status(copy(bank, token, bank.courseId(), otherModule))).as("module of another course")
			.isEqualTo(404);
		assertThat(status(copy(bank, token, otherCourse.id(), otherModule))).as("another teacher's course")
			.isEqualTo(403);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id IN (?, ?)", otherModule,
				ownSecondModule)).isZero();
	}

	// ------------------------------------------------------------------
	// CSV import.
	// ------------------------------------------------------------------

	@Test
	void klassTemplateCsvImportsUnicodeQuotedCommasWithAndWithoutBom() {
		Bank bank = seedBank("qb2-csv");
		String token = bank.fx().teacherToken();
		String csv = "question, correct answer, answer 2, answer 3 , answer 4\r\n"
				+ "\"Which is larger, 1,000 or 999?\",\"1,000\",999,,\r\n"
				+ "ශ්‍රී ලංකාවේ අගනුවර කුමක්ද?,ශ්‍රී ජයවර්ධනපුර කෝට්ටේ,කොළඹ,மதுரை,யாழ்ப்பாணம்\r\n";

		MvcResult withBom = importCsv(bank, token, "klass.csv", ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8), "k1");
		assertThat(status(withBom)).as(rawContent(withBom)).isEqualTo(201);
		JsonNode imported = data(withBom);
		assertThat(imported.path("questionCount").asInt()).isEqualTo(2);
		assertThat(imported.path("fileName").asString()).isEqualTo("klass.csv");
		assertThat(imported.path("importedByName").asString()).isNotBlank();

		MvcResult noBom = importCsv(bank, token, "plain.CSV",
				"Question,Correct Answer,Answer 2,Marks,Explanation\n2+2?,4,5,2,Basic <sum>\n"
					.getBytes(StandardCharsets.UTF_8), "k2");
		assertThat(status(noBom)).as(rawContent(noBom)).isEqualTo(201);

		JsonNode bankList = data(call(bank.host(), token,
				get("/api/v1/courses/{c}/modules/{m}/questions", bank.courseId(), bank.moduleId())));
		assertThat(bankList.path("totalElements").asInt()).isEqualTo(3);
		JsonNode first = bankList.path("content").get(0);
		assertThat(first.path("bodyHtml").asString()).isEqualTo("<p>Which is larger, 1,000 or 999?</p>");
		assertThat(first.path("importId").asString()).isEqualTo(imported.path("id").asString());
		assertThat(texts(first.path("options"), "html")).containsExactly("<p>1,000</p>", "<p>999</p>");
		assertThat(texts(first.path("options"), "correct")).containsExactly("true", "false");
		JsonNode sinhala = bankList.path("content").get(1);
		assertThat(sinhala.path("bodyHtml").asString()).isEqualTo("<p>ශ්‍රී ලංකාවේ අගනුවර කුමක්ද?</p>");
		assertThat(texts(sinhala.path("options"), "html")).containsExactly("<p>ශ්‍රී ජයවර්ධනපුර කෝට්ටේ</p>",
				"<p>කොළඹ</p>", "<p>மதுரை</p>", "<p>யாழ்ப்பாணம்</p>");
		JsonNode third = bankList.path("content").get(2);
		assertThat(third.path("marks").decimalValue()).isEqualByComparingTo("2");
		assertThat(third.path("explanationHtml").asString()).isEqualTo("<p>Basic &lt;sum&gt;</p>");
		assertThat(countRows("SELECT count(*) FROM audit_log WHERE action = 'exam_question.imported' AND target_id = ?",
				UUID.fromString(imported.path("id").asString()))).isEqualTo(1);
	}

	@Test
	void anInvalidRowRejectsTheWholeFileWith422AndImportsNothing() {
		Bank bank = seedBank("qb2-csv-bad");
		String token = bank.fx().teacherToken();
		String csv = "question,correct answer,answer 2\nGood?,yes,no\nBad?,,no\n";

		MvcResult result = importCsv(bank, token, "bad.csv", csv.getBytes(StandardCharsets.UTF_8), "bad-1");

		assertThat(status(result)).as(rawContent(result)).isEqualTo(422);
		JsonNode errors = json(result).path("error").path("details").path("errors");
		assertThat(errors.size()).isEqualTo(1);
		assertThat(errors.get(0).path("row").asInt()).isEqualTo(3);
		assertThat(errors.get(0).path("column").asString()).isEqualTo("correct answer");
		assertThat(json(result).path("error").path("code").asString()).isEqualTo("QUESTION_IMPORT_INVALID");
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id = ?", bank.moduleId())).isZero();
		assertThat(countRows("SELECT count(*) FROM question_import WHERE module_id = ?", bank.moduleId())).isZero();

		StringBuilder tooMany = new StringBuilder("question,correct answer,answer 2\n");
		for (int i = 0; i < 501; i++) {
			tooMany.append("q").append(i).append(",a,b\n");
		}
		assertThat(status(importCsv(bank, token, "big.csv", tooMany.toString().getBytes(StandardCharsets.UTF_8),
				"bad-2"))).isEqualTo(422);
		assertThat(status(importCsv(bank, token, "notes.txt", "question,correct answer,answer 2\nq,a,b\n"
			.getBytes(StandardCharsets.UTF_8), "bad-3"))).isEqualTo(422);
		assertThat(status(importCsv(bank, token, "big.csv", new byte[1024 * 1024 + 1], "bad-4"))).isEqualTo(422);
		assertThat(status(importCsv(bank, token, "q.csv", "question,correct answer,answer 2\nq,a,b\n"
			.getBytes(StandardCharsets.UTF_8), null))).as("Idempotency-Key is required").isEqualTo(400);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id = ?", bank.moduleId())).isZero();
	}

	@Test
	void replayingTheSameIdempotencyKeyReturnsTheOriginalImport() {
		Bank bank = seedBank("qb2-csv-idem");
		String token = bank.fx().teacherToken();
		byte[] csv = "question,correct answer,answer 2\nq1,a,b\nq2,c,d\n".getBytes(StandardCharsets.UTF_8);

		MvcResult first = importCsv(bank, token, "a.csv", csv, "same-key");
		MvcResult replay = importCsv(bank, token, "a.csv", csv, "same-key");

		assertThat(status(first)).isEqualTo(201);
		assertThat(status(replay)).isEqualTo(200);
		assertThat(data(replay).path("id").asString()).isEqualTo(data(first).path("id").asString());
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id = ?", bank.moduleId())).isEqualTo(2);
		assertThat(countRows("SELECT count(*) FROM question_import WHERE module_id = ?", bank.moduleId())).isEqualTo(1);
	}

	@Test
	void importHistoryIsNewestFirstSearchableAndPaginated() {
		Bank bank = seedBank("qb2-csv-history");
		String token = bank.fx().teacherToken();
		byte[] csv = "question,correct answer,answer 2\nq,a,b\n".getBytes(StandardCharsets.UTF_8);
		importCsv(bank, token, "physics-1.csv", csv, "h1");
		importCsv(bank, token, "chemistry.csv", csv, "h2");
		importCsv(bank, token, "physics-2.csv", csv, "h3");

		JsonNode all = data(call(bank.host(), token, get("/api/v1/courses/{c}/modules/{m}/question-imports?size=2",
				bank.courseId(), bank.moduleId())));
		assertThat(all.path("totalElements").asInt()).isEqualTo(3);
		assertThat(all.path("totalPages").asInt()).isEqualTo(2);
		assertThat(texts(all.path("content"), "fileName")).containsExactly("physics-2.csv", "chemistry.csv");

		JsonNode searched = data(call(bank.host(), token, get(
				"/api/v1/courses/{c}/modules/{m}/question-imports?search=PHYSICS", bank.courseId(), bank.moduleId())));
		assertThat(texts(searched.path("content"), "fileName")).containsExactly("physics-2.csv", "physics-1.csv");
		assertThat(searched.path("content").get(0).path("questionCount").asInt()).isEqualTo(1);
	}

	@Test
	void templateIsUtf8WithBomAndTheKlassHeader() throws Exception {
		Bank bank = seedBank("qb2-template");

		MvcResult result = call(bank.host(), bank.fx().teacherToken(), get("/api/v1/question-imports/template.csv"));

		assertThat(status(result)).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).startsWith("text/csv");
		byte[] body = result.getResponse().getContentAsByteArray();
		assertThat(new String(body, StandardCharsets.UTF_8))
			.isEqualTo("\uFEFFquestion,correct answer,answer 2,answer 3,answer 4\r\n");
	}

	// ------------------------------------------------------------------
	// Isolation: tenant, teacher, student, module ownership.
	// ------------------------------------------------------------------

	@Test
	void everyEndpointAnswers404ForAnotherTenantsCourseModuleOrQuestion() {
		Bank victim = seedBank("qb2-victim");
		Bank attacker = seedBank("qb2-attacker");
		UUID questionId = createQuestionOk(victim, victim.fx().teacherToken(), "<p>Secret</p>");
		String host = attacker.host();
		String token = attacker.fx().adminToken();

		for (MockHttpServletRequestBuilder request : endpointsFor(victim.courseId(), victim.moduleId(), questionId,
				attacker.courseId(), attacker.moduleId())) {
			MvcResult result = call(host, token, request);
			assertThat(status(result)).as(request.toString() + " " + rawContent(result)).isEqualTo(404);
		}
		assertThat(status(importCsv(new Bank(attacker.fx(), victim.courseId(), victim.moduleId()), token, "q.csv",
				"question,correct answer,answer 2\nq,a,b\n".getBytes(StandardCharsets.UTF_8), "x"))).isEqualTo(404);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE id = ? AND deleted_at IS NULL", questionId))
			.isEqualTo(1);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id = ?", victim.moduleId()))
			.isEqualTo(1);
	}

	@Test
	void aTeacherCannotReachAnotherTeachersCourseBank() {
		Bank bank = seedBank("qb2-teacher2");
		UUID questionId = createQuestionOk(bank, bank.fx().teacherToken(), "<p>Mine</p>");
		seedTenantUser(bank.fx().tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2 = loginAndGetToken(bank.host(), "teacher2@example.test");

		for (MockHttpServletRequestBuilder request : endpointsFor(bank.courseId(), bank.moduleId(), questionId,
				bank.courseId(), bank.moduleId())) {
			MvcResult result = call(bank.host(), teacher2, request);
			assertThat(status(result)).as(request.toString() + " " + rawContent(result)).isEqualTo(403);
		}
		assertThat(status(importCsv(bank, teacher2, "q.csv",
				"question,correct answer,answer 2\nq,a,b\n".getBytes(StandardCharsets.UTF_8), "t2"))).isEqualTo(403);
		assertThat(countRows("SELECT count(*) FROM exam_question WHERE module_id = ? AND deleted_at IS NULL",
				bank.moduleId())).isEqualTo(1);
	}

	@Test
	void studentsAreForbiddenEverywhereIncludingUploadsAndTemplate() {
		Bank bank = seedBank("qb2-student");
		UUID questionId = createQuestionOk(bank, bank.fx().teacherToken(), "<p>Q</p>");
		String student = bank.fx().studentToken();

		for (MockHttpServletRequestBuilder request : endpointsFor(bank.courseId(), bank.moduleId(), questionId,
				bank.courseId(), bank.moduleId())) {
			MvcResult result = call(bank.host(), student, request);
			assertThat(status(result)).as(request.toString() + " " + rawContent(result)).isEqualTo(403);
		}
		assertThat(status(call(bank.host(), student, get("/api/v1/question-imports/template.csv")))).isEqualTo(403);
		assertThat(status(callMultipart(bank.host(), student, multipart("/api/v1/exam-media").file(new MockMultipartFile("file", "a.png", "image/png", PNG)))))
			.isEqualTo(403);
		assertThat(status(importCsv(bank, student, "q.csv",
				"question,correct answer,answer 2\nq,a,b\n".getBytes(StandardCharsets.UTF_8), "s"))).isEqualTo(403);
	}

	@Test
	void aModuleOfAnotherCourseIsNotFoundAndATeacherAssistantMayAuthor() {
		Bank bank = seedBank("qb2-module-owner");
		var otherCourse = createCourseOrFail(bank.host(), bank.fx().adminToken(),
				newCourseRequest(uniqueSlug("qb2-mo"), bank.fx().teacher().getId(), CourseStatus.PUBLIC));
		UUID otherModule = createModuleOrFail(bank.host(), bank.fx().adminToken(), otherCourse.id(), "M", 1).id();
		String token = bank.fx().teacherToken();

		assertThat(status(createQuestion(new Bank(bank.fx(), bank.courseId(), otherModule), token,
				question("<p>q</p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false))))).isEqualTo(404);
		assertThat(status(call(bank.host(), token,
				get("/api/v1/courses/{c}/modules/{m}/questions", bank.courseId(), otherModule)))).isEqualTo(404);

		seedTenantUser(bank.fx().tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(bank.host(), "ta@example.test");
		assertThat(status(createQuestion(bank, ta,
				question("<p>by TA</p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false))))).isEqualTo(201);
	}

	/** Every id-addressed Slice A endpoint, aimed at {@code (courseId, moduleId, questionId)}. */
	private List<MockHttpServletRequestBuilder> endpointsFor(UUID courseId, UUID moduleId, UUID questionId,
			UUID copyTargetCourseId, UUID copyTargetModuleId) {
		String upsert = objectMapper
			.writeValueAsString(question("<p>x</p>", 1, null, opt("<p>a</p>", true), opt("<p>b</p>", false)));
		Map<String, Object> copyBody = new LinkedHashMap<>();
		copyBody.put("targetCourseId", copyTargetCourseId);
		copyBody.put("targetModuleId", copyTargetModuleId);
		return List.of(get("/api/v1/courses/{c}/modules/{m}/questions", courseId, moduleId),
				post("/api/v1/courses/{c}/modules/{m}/questions", courseId, moduleId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(upsert),
				post("/api/v1/courses/{c}/modules/{m}/questions/copy", courseId, moduleId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(copyBody)),
				get("/api/v1/courses/{c}/modules/question-counts", courseId),
				get("/api/v1/exam-questions/{id}", questionId),
				put("/api/v1/exam-questions/{id}", questionId).contentType(MediaType.APPLICATION_JSON).content(upsert),
				delete("/api/v1/exam-questions/{id}", questionId),
				get("/api/v1/courses/{c}/modules/{m}/question-imports", courseId, moduleId));
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	@SafeVarargs
	private static Map<String, Object> question(String body, Number marks, String explanation,
			Map<String, Object>... options) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("bodyHtml", body);
		request.put("marks", marks);
		request.put("explanationHtml", explanation);
		request.put("options", new ArrayList<>(List.of(options)));
		return request;
	}

	private static Map<String, Object> opt(String html, boolean correct) {
		return optWithId(null, html, correct);
	}

	private static Map<String, Object> optWithId(String id, String html, boolean correct) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("id", id);
		option.put("html", html);
		option.put("correct", correct);
		return option;
	}

	private MvcResult createQuestion(Bank bank, String token, Map<String, Object> body) {
		return call(bank.host(), token, post("/api/v1/courses/{c}/modules/{m}/questions", bank.courseId(),
				bank.moduleId())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private UUID createQuestionOk(Bank bank, String token, String body) {
		MvcResult result = createQuestion(bank, token,
				question(body, 1, null, opt("<p>right</p>", true), opt("<p>wrong</p>", false)));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(data(result).path("id").asString());
	}

	private MvcResult copy(Bank bank, String token, UUID targetCourseId, UUID targetModuleId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("targetCourseId", targetCourseId);
		body.put("targetModuleId", targetModuleId);
		return call(bank.host(), token, post("/api/v1/courses/{c}/modules/{m}/questions/copy", bank.courseId(),
				bank.moduleId())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private MvcResult importCsv(Bank bank, String token, String fileName, byte[] content, String idempotencyKey) {
		var builder = multipart("/api/v1/courses/{c}/modules/{m}/question-imports", bank.courseId(), bank.moduleId())
			.file(new MockMultipartFile("file", fileName, "text/csv", content));
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		return callMultipart(bank.host(), token, builder);
	}

	private UUID uploadImage(String host, String token) {
		MvcResult result = callMultipart(host, token, multipart("/api/v1/exam-media").file(new MockMultipartFile("file", "img.png", "image/png", PNG)));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(data(result).path("mediaId").asString());
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult callMultipart(String host, String token, MockMultipartHttpServletRequestBuilder builder) {
		builder.header(HttpHeaders.HOST, host);
		builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc multipart request failed", e);
		}
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private JsonNode data(MvcResult result) {
		return json(result).path("data");
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private static List<String> texts(JsonNode array, String field) {
		List<String> values = new ArrayList<>();
		for (JsonNode node : array) {
			values.add(node.path(field).asString());
		}
		return values;
	}

	private long countRows(String sql, Object... args) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
		return count == null ? 0 : count;
	}

}
