package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.videoaccessmanagement.VideoAccessManagementTestSupport;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.contentmanagement.material.web.dto.LessonMediaResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.videoaccessmanagement.web.dto.MediaAssetResponse;
import com.lms.videoaccessmanagement.web.dto.VideoAssetResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class LessonMediaIntegrationTest extends VideoAccessManagementTestSupport {

	private record Fixture(Tenant tenant, String host, String teacherToken, String studentToken, UUID courseId,
			UUID moduleId, UUID lessonId, TenantUser teacher) {
	}

	@Test
	void teacherCanAttachListUpdateAndDetachVideoWithoutDeletingTheAsset() {
		Fixture fixture = fixture("lesson-media-crud");
		VideoAssetResponse asset = uploadVideo(fixture.host(), fixture.teacherToken(), "lesson.mp4");
		HttpResult<LessonMediaResponse> attached = attach(fixture, asset.id(), "Lesson video", 1);
		assertThat(attached.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(attached.getBody().data().mediaAssetId()).isEqualTo(asset.id());
		assertThat(attached.getBody().data().provider()).isEqualTo("INTERNAL");
		assertThat(attached.getBody().data().status()).isEqualTo("READY");

		HttpResult<List<LessonMediaResponse>> listed = list(fixture.host(), fixture.teacherToken(), fixture.courseId(), fixture.lessonId());
		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listed.getBody().data()).extracting(LessonMediaResponse::mediaAssetId).containsExactly(asset.id());

		HttpResult<LessonMediaResponse> updated = update(fixture, attached.getBody().data().id(), "Renamed video", 2,
				MaterialVisibility.HIDDEN);
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updated.getBody().data().title()).isEqualTo("Renamed video");
		assertThat(updated.getBody().data().visibility()).isEqualTo(MaterialVisibility.HIDDEN);

		HttpResult<Void> detached = detach(fixture, attached.getBody().data().id());
		assertThat(detached.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listed(fixture)).isEmpty();
		assertThat(getMedia(fixture.host(), fixture.teacherToken(), asset.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void duplicateVideoAttachmentAndSequenceCollisionAreRejected() {
		Fixture fixture = fixture("lesson-media-conflict");
		VideoAssetResponse asset = uploadVideo(fixture.host(), fixture.teacherToken(), "duplicate.mp4");
		assertThat(attach(fixture, asset.id(), null, 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(attach(fixture, asset.id(), null, 2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		VideoAssetResponse secondAsset = uploadVideo(fixture.host(), fixture.teacherToken(), "second.mp4");
		assertThat(attach(fixture, secondAsset.id(), null, 1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void studentSeesOnlyVisibleLessonMediaAfterEntitlementCheck() {
		Fixture fixture = fixture("lesson-media-student");
		VideoAssetResponse asset = uploadVideo(fixture.host(), fixture.teacherToken(), "student.mp4");
		HttpResult<LessonMediaResponse> attached = attach(fixture, asset.id(), null, null);
		assertThat(listed(fixture, fixture.studentToken())).hasSize(1);
		assertThat(update(fixture.host(), fixture.teacherToken(), fixture.courseId(), fixture.lessonId(), attached.getBody().data().id(),
				"Hidden", 1, MaterialVisibility.HIDDEN).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listed(fixture, fixture.studentToken())).isEmpty();
	}

	@Tag("cross-tenant")
	@Test
	void foreignLessonAndForeignAssetAreNotAttachable() {
		Fixture a = fixture("lesson-media-iso-a");
		Fixture b = fixture("lesson-media-iso-b");
		VideoAssetResponse bAsset = uploadVideo(b.host(), b.teacherToken(), "foreign.mp4");
		assertThat(attach(a, bAsset.id(), null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		VideoAssetResponse aAsset = uploadVideo(a.host(), a.teacherToken(), "foreign-lesson.mp4");
		assertThat(attach(a.host(), a.teacherToken(), b.courseId(), b.lessonId(), aAsset.id(), null, null).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void teacherCannotMutateAnotherTeachersLesson() {
		Fixture owner = fixture("lesson-media-owner");
		TenantUser otherTeacher = seedTenantUser(owner.tenant().getId(), "other-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String otherToken = loginAndGetToken(owner.host(), "other-teacher@example.test");
		VideoAssetResponse asset = uploadVideo(owner.host(), otherToken, "other.mp4");
		assertThat(attach(owner.host(), otherToken, owner.courseId(), owner.lessonId(), asset.id(), null, null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	private Fixture fixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		enrollStudentOrFail(host, studentToken, course.id());
		return new Fixture(tenant, host, teacherToken, studentToken, course.id(), module.id(), lesson.id(), teacher);
	}

	private VideoAssetResponse uploadVideo(String host, String token, String filename) {
		return uploadVideoOrFail(host, token, mp4File(filename));
	}

	private HttpResult<LessonMediaResponse> attach(Fixture fixture, UUID assetId, String title, Integer sequence) {
		return attach(fixture.host(), fixture.teacherToken(), fixture.courseId(), fixture.lessonId(), assetId, title, sequence);
	}

	private HttpResult<LessonMediaResponse> attach(String host, String token, UUID courseId, UUID lessonId, UUID assetId,
			String title, Integer sequence) {
		String body = "{\"mediaAssetId\":\"" + assetId + "\""
				+ (title == null ? "" : ",\"title\":\"" + title + "\"")
				+ (sequence == null ? "" : ",\"sequence\":" + sequence) + "}";
		MockHttpServletRequestBuilder request = post("/api/v1/courses/{courseId}/lessons/{lessonId}/media", courseId, lessonId)
			.contentType(MediaType.APPLICATION_JSON).content(body);
		return parseSingle(perform(authenticated(request, host, token)), LessonMediaResponse.class);
	}

	private List<LessonMediaResponse> listed(Fixture fixture) {
		return listed(fixture, fixture.teacherToken());
	}

	private List<LessonMediaResponse> listed(Fixture fixture, String token) {
		return list(fixture.host(), token, fixture.courseId(), fixture.lessonId()).getBody().data();
	}

	private HttpResult<List<LessonMediaResponse>> list(String host, String token, UUID courseId, UUID lessonId) {
		return parseList(perform(authenticated(get("/api/v1/courses/{courseId}/lessons/{lessonId}/media", courseId, lessonId), host,
				token)), LessonMediaResponse.class);
	}

	private HttpResult<LessonMediaResponse> update(Fixture fixture, UUID id, String title, int sequence,
			MaterialVisibility visibility) {
		return update(fixture.host(), fixture.teacherToken(), fixture.courseId(), fixture.lessonId(), id, title, sequence, visibility);
	}

	private HttpResult<LessonMediaResponse> update(String host, String token, UUID courseId, UUID lessonId, UUID id, String title,
			int sequence, MaterialVisibility visibility) {
		String body = "{\"title\":\"" + title + "\",\"sequence\":" + sequence + ",\"visibility\":\""
				+ visibility.name() + "\"}";
		MockHttpServletRequestBuilder request = patch("/api/v1/courses/{courseId}/lessons/{lessonId}/media/{mediaId}", courseId,
				lessonId, id).contentType(MediaType.APPLICATION_JSON).content(body);
		return parseSingle(perform(authenticated(request, host, token)), LessonMediaResponse.class);
	}

	private HttpResult<Void> detach(Fixture fixture, UUID id) {
		return parseSingle(perform(authenticated(delete("/api/v1/courses/{courseId}/lessons/{lessonId}/media/{mediaId}",
				fixture.courseId(), fixture.lessonId(), id), fixture.host(), fixture.teacherToken())), Void.class);
	}

	private HttpResult<MediaAssetResponse> getMedia(String host, String token, UUID id) {
		return parseSingle(perform(authenticated(get("/api/v1/media/{id}", id), host, token)), MediaAssetResponse.class);
	}

}
