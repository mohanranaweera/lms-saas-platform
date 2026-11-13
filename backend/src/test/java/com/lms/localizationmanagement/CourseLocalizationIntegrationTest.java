package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I18N-01 section 8: public course title/description variants. Drafts are never public; the
 * storefront serves the PUBLISHED variant of the resolved locale, else the original; slug/id
 * are unchanged; other tenants' courses are not addressable.
 */
class CourseLocalizationIntegrationTest extends I18nTestSupport {

	private record CourseFx(Fx fx, TenantUser teacher, String teacherToken, CourseResponse course) {
	}

	private CourseFx course(String prefix) {
		Fx fx = seed(prefix);
		TenantUser teacher = seedTenantUser(fx.tenantId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		CourseResponse course = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		return new CourseFx(fx, teacher, loginAndGetToken(fx.host(), "teacher@example.test"), course);
	}

	private MvcResult saveVariant(Fx fx, String token, UUID courseId, String locale, String title, Long expected) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("description", "Synthetic " + locale + " description");
		body.put("expectedVersion", expected);
		return json(fx.host(), token, put("/api/v1/courses/{c}/localizations/{l}", courseId, locale), body);
	}

	private JsonNode publicCourse(String host, String slug, String locale) {
		var builder = get("/api/v1/public/courses/{slug}", slug);
		if (locale != null) {
			builder.header("X-LMS-Locale", locale);
		}
		MvcResult result = call(host, null, builder);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	@Test
	void draftVariantIsPrivateAndPublishedVariantIsServedForItsLocaleOnly() {
		CourseFx c = course("i18n-course");
		Fx fx = c.fx();
		setLanguagesOk(fx, List.of("en-LK", "si-LK", "ta-LK"), "en-LK", false);
		UUID courseId = c.course().id();
		String slug = c.course().slug();
		String originalName = c.course().name();

		MvcResult saved = saveVariant(fx, fx.adminToken(), courseId, "si-LK", "සිංහල පාඨමාලාව", null);
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
		assertThat(data(saved).path("status").asString()).isEqualTo("DRAFT");

		JsonNode draftPublic = publicCourse(fx.host(), slug, "si-LK");
		assertThat(draftPublic.path("name").asString()).isEqualTo(originalName);
		assertThat(draftPublic.path("contentLocale").isNull()).isTrue();
		assertThat(draftPublic.path("availableLocales").toString()).isEqualTo("[]");

		MvcResult published = json(fx.host(), fx.adminToken(),
				post("/api/v1/courses/{c}/localizations/si-LK/publish", courseId), Map.of());
		assertThat(status(published)).as(rawContent(published)).isEqualTo(200);
		assertThat(data(published).path("status").asString()).isEqualTo("PUBLISHED");

		JsonNode si = publicCourse(fx.host(), slug, "si-LK");
		assertThat(si.path("name").asString()).isEqualTo("සිංහල පාඨමාලාව");
		assertThat(si.path("description").asString()).isEqualTo("Synthetic si-LK description");
		assertThat(si.path("contentLocale").asString()).isEqualTo("si-LK");
		assertThat(si.path("availableLocales").toString()).isEqualTo("[\"si-LK\"]");
		assertThat(si.path("slug").asString()).isEqualTo(slug);
		assertThat(si.path("id").asString()).isEqualTo(courseId.toString());
		// Other locales / no hint: the original.
		assertThat(publicCourse(fx.host(), slug, "ta-LK").path("name").asString()).isEqualTo(originalName);
		JsonNode none = publicCourse(fx.host(), slug, null);
		assertThat(none.path("name").asString()).isEqualTo(originalName);
		assertThat(none.path("contentLocale").isNull()).isTrue();
		// The listing uses the same rules (locale query param).
		MvcResult list = call(fx.host(), null, get("/api/v1/public/courses?locale=si-LK"));
		assertThat(status(list)).isEqualTo(200);
		assertThat(rawContent(list)).contains("සිංහල පාඨමාලාව");

		// Editing a published variant does not change public text until it is published again.
		long version = data(published).path("version").asLong();
		MvcResult edited = saveVariant(fx, fx.adminToken(), courseId, "si-LK", "නව නම", version);
		assertThat(status(edited)).as(rawContent(edited)).isEqualTo(200);
		assertThat(data(edited).path("hasUnpublishedChanges").asBoolean()).isTrue();
		assertThat(publicCourse(fx.host(), slug, "si-LK").path("name").asString()).isEqualTo("සිංහල පාඨමාලාව");
		MvcResult stale = saveVariant(fx, fx.adminToken(), courseId, "si-LK", "තවත්", version);
		assertThat(status(stale)).isEqualTo(409);
		assertThat(errorCode(stale)).isEqualTo("COURSE_LOCALIZATION_STALE");

		// Disabling the language hides the variant from the storefront.
		setLanguagesOk(fx, List.of("en-LK", "ta-LK"), "en-LK", null);
		JsonNode disabled = publicCourse(fx.host(), slug, "si-LK");
		assertThat(disabled.path("name").asString()).isEqualTo(originalName);
		assertThat(disabled.path("availableLocales").toString()).isEqualTo("[]");

		// Unpublish.
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", null);
		MvcResult unpublished = json(fx.host(), fx.adminToken(),
				post("/api/v1/courses/{c}/localizations/si-LK/unpublish", courseId), Map.of());
		assertThat(status(unpublished)).as(rawContent(unpublished)).isEqualTo(200);
		assertThat(publicCourse(fx.host(), slug, "si-LK").path("name").asString()).isEqualTo(originalName);

		MvcResult variants = call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/localizations", courseId));
		assertThat(status(variants)).isEqualTo(200);
		assertThat(data(variants).path("variants")).hasSize(1);
		assertThat(data(variants).path("contentLocale").isNull()).isTrue();
		assertThat(auditCount(fx.tenantId(), "course_localization.published")).isEqualTo(1);
	}

	@Test
	void editingFollowsCourseEditAuthorizationAndTenantIsolation() {
		CourseFx a = course("i18n-course-a");
		CourseFx b = course("i18n-course-b");
		UUID courseB = b.course().id();

		// The owning teacher may edit their own course's variants.
		MvcResult own = saveVariant(a.fx(), a.teacherToken(), a.course().id(), "ta-LK", "தமிழ் பாடநெறி", null);
		assertThat(status(own)).as(rawContent(own)).isEqualTo(200);
		// Another teacher of the same tenant may not.
		seedTenantUser(a.fx().tenantId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(a.fx().host(), "other-teacher@example.test");
		assertThat(status(saveVariant(a.fx(), otherTeacher, a.course().id(), "si-LK", "x", null))).isEqualTo(403);
		// A student may not.
		String student = userToken(a.fx(), "student@example.test", Role.STUDENT);
		assertThat(status(call(a.fx().host(), student, get("/api/v1/courses/{c}/localizations", a.course().id()))))
			.isEqualTo(403);

		// Tenant A's admin addressing tenant B's course id: 404, nothing written.
		assertThat(status(saveVariant(a.fx(), a.fx().adminToken(), courseB, "si-LK", "x", null))).isEqualTo(404);
		assertThat(status(call(a.fx().host(), a.fx().adminToken(), get("/api/v1/courses/{c}/localizations", courseB))))
			.isEqualTo(404);
		assertThat(status(json(a.fx().host(), a.fx().adminToken(),
				post("/api/v1/courses/{c}/localizations/si-LK/publish", courseB), Map.of()))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM course_localization WHERE course_id = ?", courseB)).isZero();

		// Unsupported locale.
		MvcResult unsupported = saveVariant(a.fx(), a.fx().adminToken(), a.course().id(), "fr-FR", "x", null);
		assertThat(status(unsupported)).isEqualTo(400);
		assertThat(errorCode(unsupported)).isEqualTo("LOCALE_NOT_SUPPORTED");
		// Missing variant.
		assertThat(status(call(a.fx().host(), a.fx().adminToken(),
				get("/api/v1/courses/{c}/localizations/si-LK", a.course().id())))).isEqualTo(404);
	}

}
