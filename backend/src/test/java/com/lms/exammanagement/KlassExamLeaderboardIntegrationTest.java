package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Klass Exam Module Slice E leaderboards (D7) over real HTTP + Postgres: validation (< 2 exams,
 * duplicates, name, foreign-tenant exam), teacher / teacher-assistant / tenant scope, ranking
 * with shared ranks (1,1,3), unpublished exams contributing nothing, unattended enrolled = 0,
 * share / maxPossible, search + pagination, student visibility and masking, update, delete
 * (grouping only) and audit.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class KlassExamLeaderboardIntegrationTest extends KlassExamSliceETestSupport {

	@Test
	void leaderboardCrudRankingAndVisibility() {
		ExamFixture fx = seedExamFixture("kl-board");
		UUID tenantId = fx.tenant().getId();
		Instant start = T0.plus(1, ChronoUnit.HOURS);
		Instant end = start.plus(1, ChronoUnit.HOURS);
		UUID a = createPhysical(fx, fx.teacherToken(), fx.course().id(), "Exam A", start, end);
		UUID b = createPhysical(fx, fx.teacherToken(), fx.course().id(), "Exam B", start, end);
		UUID c = createPhysical(fx, fx.teacherToken(), fx.course().id(), "Exam C", start, end);

		UUID s1 = fx.student().getId();
		addProfile(tenantId, s1, "Amal Perera");
		Student s2 = loggedInStudent(fx, "kl-s2@example.test", "Bimal Silva");
		UUID s3 = studentWithProfile(tenantId, "kl-s3@example.test", "Chamara Fernando");
		Student s4 = loggedInStudent(fx, "kl-s4@example.test", "Dilan Jayasuriya");
		Student outsider = loggedInStudent(fx, "kl-s5@example.test", "Eranga Outsider");
		for (UUID studentId : List.of(s1, s2.id(), s3)) {
			enrollDirect(tenantId, a, studentId);
		}
		for (UUID studentId : List.of(s1, s2.id(), s4.id())) {
			enrollDirect(tenantId, b, studentId);
		}
		for (UUID studentId : List.of(s1, s3)) {
			enrollDirect(tenantId, c, studentId);
		}

		// Validation.
		MvcResult one = createLeaderboard(fx.host(), fx.adminToken(), "Term", List.of(a), false);
		assertThat(status(one)).isEqualTo(400);
		assertThat(fieldErrors(one)).contains("examIds");
		assertThat(status(createLeaderboard(fx.host(), fx.adminToken(), "Term", List.of(a, a), false)))
			.isEqualTo(400);
		MvcResult blank = createLeaderboard(fx.host(), fx.adminToken(), "  ", List.of(a, b), false);
		assertThat(status(blank)).isEqualTo(400);
		assertThat(fieldErrors(blank)).contains("name");
		assertThat(status(createLeaderboard(fx.host(), fx.adminToken(), "x".repeat(121), List.of(a, b), false)))
			.isEqualTo(400);
		ExamFixture other = seedExamFixture("kl-board-other");
		UUID foreign = createPhysical(other, other.teacherToken(), other.course().id(), "Foreign", start, end);
		assertThat(status(createLeaderboard(fx.host(), fx.adminToken(), "Term", List.of(a, foreign), false)))
			.isEqualTo(404);
		assertThat(status(createLeaderboard(fx.host(), fx.studentToken(), "Term", List.of(a, b), false)))
			.isEqualTo(403);
		seedTenantUser(tenantId, "kl-ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(fx.host(), "kl-ta@example.test");
		assertThat(status(createLeaderboard(fx.host(), ta, "Term", List.of(a, b), false))).isEqualTo(403);

		// Another course's teacher can't group these exams.
		TenantUser otherTeacher = seedTenantUser(tenantId, "kl-t2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fx.host(), "kl-t2@example.test");
		CourseResponse otherCourse = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("kl-c2"), otherTeacher.getId(), CourseStatus.PUBLIC));
		UUID d = createPhysical(fx, otherTeacherToken, otherCourse.id(), "Exam D", start, end);
		assertThat(status(createLeaderboard(fx.host(), otherTeacherToken, "Mixed", List.of(a, d), false)))
			.isEqualTo(403);

		// The owning teacher creates (staff-only by default).
		MvcResult created = createLeaderboard(fx.host(), fx.teacherToken(), "  Term 1  ", List.of(a, b, c), null);
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		UUID boardId = UUID.fromString(data(created).path("id").asString());
		assertThat(data(created).path("name").asString()).isEqualTo("Term 1");
		assertThat(data(created).path("published").asBoolean()).isFalse();
		assertThat(data(created).path("exams")).hasSize(3);
		assertThat(auditCount("exam_leaderboard.created", boardId)).isEqualTo(1);

		// Scope: other teacher 403 and not listed; TA reads; other tenant 404.
		assertThat(status(getLeaderboard(fx.host(), otherTeacherToken, boardId))).isEqualTo(403);
		assertThat(data(listLeaderboards(fx.host(), otherTeacherToken))).isEmpty();
		assertThat(status(getLeaderboard(fx.host(), ta, boardId))).isEqualTo(200);
		assertThat(status(deleteLeaderboard(fx.host(), ta, boardId))).isEqualTo(403);
		assertThat(status(getLeaderboard(other.host(), other.adminToken(), boardId))).isEqualTo(404);
		assertThat(status(ranks(other.host(), other.adminToken(), boardId, null, 0, 20))).isEqualTo(404);
		assertThat(status(deleteLeaderboard(other.host(), other.adminToken(), boardId))).isEqualTo(404);
		JsonNode list = data(listLeaderboards(fx.host(), fx.adminToken()));
		assertThat(list).hasSize(1);
		assertThat(list.get(0).path("examCount").asInt()).isEqualTo(3);

		// No published marks yet: empty state.
		JsonNode before = data(ranks(fx.host(), fx.adminToken(), boardId, null, 0, 20));
		assertThat(before.path("hasPublishedMarks").asBoolean()).isFalse();
		assertThat(before.path("rows")).isEmpty();
		assertThat(before.path("exams")).hasSize(3);

		// Marks: A (100): s1 80, s2 70, s3 absent. B (50): s1 20, s2 30, s4 none. C (40, never published).
		now.set(start.plus(5, ChronoUnit.MINUTES));
		totalMarks(fx, a, 100);
		totalMarks(fx, b, 50);
		totalMarks(fx, c, 40);
		marks(fx, a, s1, 80, null);
		marks(fx, a, s2.id(), 70, null);
		marks(fx, a, s3, null, "ABSENT");
		marks(fx, b, s1, 20, null);
		marks(fx, b, s2.id(), 30, null);
		marks(fx, c, s1, 40, null);
		marks(fx, c, s3, 40, null);
		now.set(end.plus(1, ChronoUnit.MINUTES));
		notifyOk(fx.host(), fx.teacherToken(), a, "a-1", null);
		notifyOk(fx.host(), fx.teacherToken(), b, "b-1", null);

		JsonNode ranked = data(ranks(fx.host(), fx.adminToken(), boardId, null, 0, 20));
		assertThat(ranked.path("hasPublishedMarks").asBoolean()).isTrue();
		assertThat(ranked.path("totalElements").asLong()).isEqualTo(4);
		JsonNode rows = ranked.path("rows");
		assertThat(texts(rows, "name")).containsExactly("Amal Perera", "Bimal Silva", "Chamara Fernando",
				"Dilan Jayasuriya");
		assertThat(List.of(rows.get(0).path("rank").asInt(), rows.get(1).path("rank").asInt(),
				rows.get(2).path("rank").asInt(), rows.get(3).path("rank").asInt()))
			.containsExactly(1, 1, 3, 3);
		JsonNode amal = rowFor(rows, "Amal Perera");
		assertThat(decimal(amal.path("totalMarks"))).isEqualByComparingTo("100");
		assertThat(decimal(amal.path("maxPossible"))).isEqualByComparingTo("150");
		assertThat(decimal(amal.path("share"))).isEqualByComparingTo("66.7");
		assertThat(amal.path("studentId").asString()).isEqualTo(s1.toString());
		JsonNode chamara = rowFor(rows, "Chamara Fernando");
		assertThat(decimal(chamara.path("totalMarks"))).isEqualByComparingTo("0");
		assertThat(decimal(chamara.path("maxPossible"))).isEqualByComparingTo("100");
		assertThat(decimal(chamara.path("share"))).isEqualByComparingTo("0.0");
		JsonNode dilan = rowFor(rows, "Dilan Jayasuriya");
		assertThat(decimal(dilan.path("maxPossible"))).isEqualByComparingTo("50");
		// Unpublished C contributes nothing and its marks never appear.
		for (JsonNode row : rows) {
			for (JsonNode perExam : row.path("perExam")) {
				if (perExam.path("examId").asString().equals(c.toString())) {
					assertThat(perExam.path("marks").isNull()).isTrue();
				}
			}
		}
		for (JsonNode exam : ranked.path("exams")) {
			assertThat(exam.path("published").asBoolean()).isEqualTo(!exam.path("id").asString().equals(c.toString()));
		}
		// Search and pagination keep ranks.
		JsonNode searched = data(ranks(fx.host(), fx.adminToken(), boardId, "dilan", 0, 20));
		assertThat(searched.path("rows")).hasSize(1);
		assertThat(searched.path("rows").get(0).path("rank").asInt()).isEqualTo(3);
		JsonNode page2 = data(ranks(fx.host(), fx.adminToken(), boardId, null, 1, 2));
		assertThat(texts(page2.path("rows"), "name")).containsExactly("Chamara Fernando", "Dilan Jayasuriya");
		assertThat(page2.path("totalPages").asInt()).isEqualTo(2);

		// Students: nothing while unpublished.
		assertThat(data(studentLeaderboards(fx.host(), s4.token()))).isEmpty();
		assertThat(status(studentRanks(fx.host(), s4.token(), boardId))).isEqualTo(404);
		assertThat(status(ranks(fx.host(), s4.token(), boardId, null, 0, 20))).isEqualTo(403);

		MvcResult published = updateLeaderboard(fx.host(), fx.teacherToken(), boardId, "Term 1", List.of(a, b, c),
				true);
		assertThat(status(published)).as(rawContent(published)).isEqualTo(200);
		assertThat(auditCount("exam_leaderboard.updated", boardId)).isEqualTo(1);

		JsonNode mine = data(studentLeaderboards(fx.host(), s4.token()));
		assertThat(mine).hasSize(1);
		assertThat(mine.get(0).path("id").asString()).isEqualTo(boardId.toString());
		JsonNode studentView = data(studentRanks(fx.host(), s4.token(), boardId));
		assertThat(studentView.path("rows")).hasSize(4);
		JsonNode self = rowFor(studentView.path("rows"), "Dilan Jayasuriya");
		assertThat(self.path("self").asBoolean()).isTrue();
		assertThat(self.path("studentId").asString()).isEqualTo(s4.id().toString());
		JsonNode peer = rowFor(studentView.path("rows"), "Amal Perera");
		assertThat(peer.path("self").asBoolean()).isFalse();
		assertThat(peer.path("studentId").isNull()).isTrue();
		assertThat(peer.path("indexNumber").isNull()).isTrue();
		assertThat(peer.path("perExam")).isEmpty();
		assertThat(decimal(peer.path("totalMarks"))).isEqualByComparingTo("100");
		// A student enrolled in none of its exams doesn't see it; staff endpoints stay closed.
		assertThat(data(studentLeaderboards(fx.host(), outsider.token()))).isEmpty();
		assertThat(status(studentRanks(fx.host(), outsider.token(), boardId))).isEqualTo(404);
		assertThat(status(studentLeaderboards(fx.host(), fx.adminToken()))).isEqualTo(403);

		// Update replaces the exam set; < 2 still refused.
		assertThat(status(updateLeaderboard(fx.host(), fx.teacherToken(), boardId, "Term 1", List.of(a), true)))
			.isEqualTo(400);
		MvcResult narrowed = updateLeaderboard(fx.host(), fx.adminToken(), boardId, "Term 1 (A+C)", List.of(a, c),
				true);
		assertThat(status(narrowed)).as(rawContent(narrowed)).isEqualTo(200);
		assertThat(data(narrowed).path("examIds")).hasSize(2);
		// s4 was only in B: the leaderboard disappears for them.
		assertThat(data(studentLeaderboards(fx.host(), s4.token()))).isEmpty();
		assertThat(countRows("SELECT count(*) FROM exam_leaderboard_exam WHERE leaderboard_id = ?", boardId))
			.isEqualTo(2);

		// Delete removes the grouping only.
		assertThat(status(deleteLeaderboard(fx.host(), otherTeacherToken, boardId))).isEqualTo(403);
		assertThat(status(deleteLeaderboard(fx.host(), fx.adminToken(), boardId))).isEqualTo(204);
		assertThat(status(getLeaderboard(fx.host(), fx.adminToken(), boardId))).isEqualTo(404);
		assertThat(countRows("SELECT count(*) FROM exam_leaderboard_exam WHERE leaderboard_id = ?", boardId))
			.isZero();
		assertThat(countRows("SELECT count(*) FROM exam WHERE id IN (?, ?, ?) AND deleted_at IS NULL", a, b, c))
			.isEqualTo(3);
		assertThat(countRows("SELECT count(*) FROM exam_result WHERE exam_id IN (?, ?, ?)", a, b, c)).isEqualTo(7);
		assertThat(auditCount("exam_leaderboard.deleted", boardId)).isEqualTo(1);
	}

}
