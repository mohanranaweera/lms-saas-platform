package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Klass exam directory with the injected {@link Clock} fixed (own Spring context, like
 * {@code ExamLifecycleClockBoundaryIntegrationTest}): server-derived grouping at the exact
 * start / end boundaries, ordering, search on exam and course name, teacher scoping, and
 * that no password ever appears in the payload.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class ExamDirectoryClockIntegrationTest extends KlassExamTestSupport {

	private static final Instant T0 = Instant.parse("2026-11-15T08:00:00Z");

	@MockitoBean
	private Clock clock;

	@Test
	void groupsByDerivedStatusAtTheExactBoundariesOrdersAndSearches() {
		ExamFixture fx = seedExamFixture("kx-dir");
		when(clock.instant()).thenReturn(T0);
		UUID moduleId = createModuleOrFail(fx.host(), fx.adminToken(), fx.course().id(), "Module", 1).id();
		createBankQuestion(fx.host(), fx.teacherToken(), fx.course().id(), moduleId, "<p>Q</p>", 2);

		Instant alphaStart = T0.plus(1, ChronoUnit.HOURS);
		Instant alphaEnd = T0.plus(2, ChronoUnit.HOURS);
		UUID alpha = id(createExamOk(fx.host(), fx.teacherToken(),
				physicalBody("Alpha practical", fx.course().id(), alphaStart, alphaEnd)));
		UUID beta = id(createExamOk(fx.host(), fx.teacherToken(), mcqBody("Beta quiz", fx.course().id(),
				T0.plus(3, ChronoUnit.HOURS), T0.plus(4, ChronoUnit.HOURS), 1, 1, List.of(quota(moduleId, 1)))));
		UUID running = id(createExamOk(fx.host(), fx.teacherToken(), physicalBody("Running now", fx.course().id(),
				T0.minus(1, ChronoUnit.HOURS), T0.plus(5, ChronoUnit.HOURS))));
		UUID oldOne = id(createExamOk(fx.host(), fx.teacherToken(), physicalBody("Old one", fx.course().id(),
				T0.minus(5, ChronoUnit.HOURS), T0.minus(4, ChronoUnit.HOURS))));

		MvcResult first = call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory"));
		assertThat(status(first)).as(rawContent(first)).isEqualTo(200);
		assertThat(rawContent(first)).doesNotContain(PASSWORD).doesNotContain("password\"");
		JsonNode d = data(first);
		assertThat(Instant.parse(d.path("serverNow").asString())).isEqualTo(T0);
		assertThat(ids(d.path("upcoming"))).containsExactly(alpha.toString(), beta.toString());
		assertThat(ids(d.path("inProgress"))).containsExactly(running.toString());
		assertThat(ids(d.path("previous"))).containsExactly(oldOne.toString());
		JsonNode betaCard = d.path("upcoming").get(1);
		assertThat(betaCard.path("type").asString()).isEqualTo("MCQ");
		assertThat(betaCard.path("status").asString()).isEqualTo("UPCOMING");
		assertThat(betaCard.path("questionCount").asInt()).isEqualTo(1);
		assertThat(betaCard.path("totalMarks").decimalValue()).isEqualByComparingTo("2");
		assertThat(betaCard.path("courseName").asString()).isNotBlank();
		assertThat(betaCard.path("enrolled").asLong()).isZero();
		assertThat(betaCard.path("attended").asLong()).isZero();
		assertThat(betaCard.path("completed").asLong()).isZero();
		assertThat(betaCard.path("submitted").asLong()).isZero();

		// Exactly at startAt the exam is in progress (startAt <= now < endAt).
		when(clock.instant()).thenReturn(alphaStart);
		JsonNode atStart = data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory")));
		assertThat(ids(atStart.path("inProgress"))).containsExactly(alpha.toString(), running.toString());
		assertThat(atStart.path("inProgress").get(0).path("status").asString()).isEqualTo("IN_PROGRESS");
		// One instant before endAt still in progress; exactly at endAt it is previous.
		when(clock.instant()).thenReturn(alphaEnd.minusMillis(1));
		assertThat(ids(data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory"))).path("inProgress")))
			.contains(alpha.toString());
		when(clock.instant()).thenReturn(alphaEnd);
		JsonNode atEnd = data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory")));
		assertThat(ids(atEnd.path("previous"))).containsExactly(alpha.toString(), oldOne.toString());
		assertThat(atEnd.path("previous").get(0).path("status").asString()).isEqualTo("FINISHED");
		assertThat(ids(atEnd.path("upcoming"))).containsExactly(beta.toString());

		// Search: case-insensitive on exam name and on course name.
		JsonNode byName = data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory").param("search",
				"  ALPHA ")));
		assertThat(allIds(byName)).containsExactly(alpha.toString());
		String courseName = betaCard.path("courseName").asString();
		JsonNode byCourse = data(call(fx.host(), fx.teacherToken(),
				get("/api/v1/exams/directory").param("search", courseName.toUpperCase())));
		assertThat(allIds(byCourse)).hasSize(4);
		JsonNode none = data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory").param("search",
				"no-such-exam")));
		assertThat(allIds(none)).isEmpty();
	}

	@Test
	void aTeacherSeesOnlyOwnCoursesWhileStaffAndAssistantsSeeTheTenant() {
		ExamFixture fx = seedExamFixture("kx-dir-scope");
		when(clock.instant()).thenReturn(T0);
		TenantUser teacher2 = seedTenantUser(fx.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2Token = loginAndGetToken(fx.host(), "teacher2@example.test");
		UUID course2 = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("kx-dir2"), teacher2.getId(), CourseStatus.PUBLIC)).id();
		UUID mine = id(createExamOk(fx.host(), fx.teacherToken(),
				physicalBody("Mine", fx.course().id(), T0.plusSeconds(60), T0.plusSeconds(120))));
		UUID theirs = id(createExamOk(fx.host(), teacher2Token,
				physicalBody("Theirs", course2, T0.plusSeconds(60), T0.plusSeconds(120))));
		seedTenantUser(fx.tenant().getId(), "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		String ta = loginAndGetToken(fx.host(), "ta@example.test");

		assertThat(allIds(data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory")))))
			.containsExactly(mine.toString());
		assertThat(allIds(data(call(fx.host(), teacher2Token, get("/api/v1/exams/directory")))))
			.containsExactly(theirs.toString());
		// Searching cannot widen a teacher's scope.
		assertThat(allIds(data(call(fx.host(), fx.teacherToken(), get("/api/v1/exams/directory").param("search",
				"Theirs"))))).isEmpty();
		assertThat(allIds(data(call(fx.host(), fx.adminToken(), get("/api/v1/exams/directory")))))
			.containsExactlyInAnyOrder(mine.toString(), theirs.toString());
		assertThat(allIds(data(call(fx.host(), ta, get("/api/v1/exams/directory")))))
			.containsExactlyInAnyOrder(mine.toString(), theirs.toString());
		assertThat(status(call(fx.host(), fx.studentToken(), get("/api/v1/exams/directory")))).isEqualTo(403);
	}

	private static UUID id(JsonNode exam) {
		return UUID.fromString(exam.path("id").asString());
	}

	private static List<String> ids(JsonNode cards) {
		return texts(cards, "id");
	}

	private static List<String> allIds(JsonNode directory) {
		List<String> all = new java.util.ArrayList<>();
		all.addAll(ids(directory.path("inProgress")));
		all.addAll(ids(directory.path("upcoming")));
		all.addAll(ids(directory.path("previous")));
		return all;
	}

}
