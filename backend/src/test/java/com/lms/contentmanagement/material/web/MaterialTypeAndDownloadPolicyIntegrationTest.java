package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.ApiErrorCodes;
import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.web.dto.MaterialDownloadUrlResponse;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed HTTP coverage for Wave 5's material-type
 * discriminator (plan §3/§4, PAR-06-03/PAR-06-05/PAR-27-01): per-type
 * required-field validation on create, the {@code LINK}-type raw-URL
 * download-url exemption, the {@code NOTE}-type "no download action"
 * decision, and the download-limit/availability-window enforcement on
 * {@code GET .../download-url}. Uses the owning Teacher throughout (not a
 * Student) - none of this behavior is role-gated beyond the pre-existing
 * ownership check {@code MaterialAccessGuard}/{@code
 * MaterialFetchVisibilityIntegrationTest} already cover; publishing the
 * course is unnecessary since the Teacher branch never checks {@code
 * coursePublished()}.
 */
class MaterialTypeAndDownloadPolicyIntegrationTest extends ContentManagementTestSupport {

	private record Fixture(String host, String teacherToken, UUID courseId, UUID moduleId, UUID lessonId) {
	}

	private Fixture seedFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug(prefix), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		return new Fixture(host, teacherToken, course.id(), module.id(), lesson.id());
	}

	// ------------------------------------------------------------------
	// Per-materialType required-field validation (plan §8).
	// ------------------------------------------------------------------

	@Test
	void linkMaterialWithoutAnExternalUrlIsRejectedWith400() {
		Fixture f = seedFixture("mtype-link-missing-url");

		HttpResult<MaterialResponse> result = createMaterialRaw(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "External Video", MaterialType.LINK, null, null, null, null, null, null, null, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.VALIDATION_ERROR);
	}

	@Test
	void linkMaterialWithAnImplausibleExternalUrlIsRejectedWith400() {
		Fixture f = seedFixture("mtype-link-bad-url");

		HttpResult<MaterialResponse> result = createMaterialRaw(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "External Video", MaterialType.LINK, null, "not-a-url", null, null, null, null, null,
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void noteMaterialWithoutNoteContentIsRejectedWith400() {
		Fixture f = seedFixture("mtype-note-missing-content");

		HttpResult<MaterialResponse> result = createMaterialRaw(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Lecture Note", MaterialType.NOTE, null, null, null, null, null, null, null, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.VALIDATION_ERROR);
	}

	@Test
	void videoMaterialWithoutAVideoAssetIdIsRejectedWith400() {
		Fixture f = seedFixture("mtype-video-missing-asset");

		HttpResult<MaterialResponse> result = createMaterialRaw(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Instructional Video", MaterialType.VIDEO, null, null, null, null, null, null, null,
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void recordingMaterialWithoutAVideoAssetIdIsRejectedWith400() {
		Fixture f = seedFixture("mtype-recording-missing-asset");

		HttpResult<MaterialResponse> result = createMaterialRaw(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Class Recording", MaterialType.RECORDING, null, null, null, null, null, null, null,
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void uploadRequiredMaterialTypeWithoutAFileIsRejectedWith400() {
		Fixture f = seedFixture("mtype-pdf-missing-file");

		HttpResult<MaterialResponse> result = createMaterialRaw(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Slides", MaterialType.PDF, null, null, null, null, null, null, null, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.VALIDATION_ERROR);
	}

	// ------------------------------------------------------------------
	// LINK - raw externalUrl, no signed URL, no download-count enforcement
	// (plan §10 item 2).
	// ------------------------------------------------------------------

	@Test
	void linkMaterialSucceedsAndItsDownloadUrlIsTheRawExternalUrlNotASignedUrl() {
		Fixture f = seedFixture("mtype-link-success");
		String externalUrl = "https://videos.example.test/watch?v=abc123";

		MaterialResponse created = createLinkMaterialOrFail(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "External Video", externalUrl);
		assertThat(created.materialType()).isEqualTo(MaterialType.LINK);
		assertThat(created.externalUrl()).isEqualTo(externalUrl);

		HttpResult<MaterialDownloadUrlResponse> downloadUrl = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), created.id());

		assertThat(downloadUrl.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(downloadUrl.getBody().data().url()).isEqualTo(externalUrl);
	}

	// ------------------------------------------------------------------
	// NOTE - no download action at all (this module's judgment call, see
	// MaterialService#getDownloadUrl's javadoc).
	// ------------------------------------------------------------------

	@Test
	void noteMaterialSucceedsButItsDownloadUrlEndpointReturns404() {
		Fixture f = seedFixture("mtype-note-success");

		MaterialResponse created = createNoteMaterialOrFail(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Lecture Note", "Remember to review chapter 3 before the exam.");
		assertThat(created.materialType()).isEqualTo(MaterialType.NOTE);
		assertThat(created.noteContent()).isEqualTo("Remember to review chapter 3 before the exam.");

		HttpResult<MaterialDownloadUrlResponse> downloadUrl = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), created.id());

		assertThat(downloadUrl.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Download-limit enforcement (plan §8).
	// ------------------------------------------------------------------

	@Test
	void aMaterialsDownloadUrlSucceedsExactlyMaxDownloadsTimesThenReturns403DownloadLimitReached() {
		Fixture f = seedFixture("mtype-download-limit");
		MaterialResponse material = createFileMaterialOrFail(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Limited Handout", MaterialType.PDF, pdfFile("handout.pdf"), 2, null, null);

		HttpResult<MaterialDownloadUrlResponse> first = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), material.id());
		HttpResult<MaterialDownloadUrlResponse> second = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), material.id());
		HttpResult<MaterialDownloadUrlResponse> third = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), material.id());

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(third.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(third.getBody().error().code()).isEqualTo(ApiErrorCodes.DOWNLOAD_LIMIT_REACHED);

		HttpResult<MaterialResponse> fetched = getMaterial(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), material.id());
		assertThat(fetched.getBody().data().downloadCount()).isEqualTo(2);
	}

	/**
	 * Proves {@code MaterialRepository#incrementDownloadCountIfUnderLimit}'s
	 * atomic guarded {@code UPDATE} - not a read-then-write race - actually
	 * holds under real concurrency: {@code maxDownloads = 1}, N parallel
	 * requests, exactly one must succeed.
	 */
	@Test
	void concurrentDownloadUrlRequestsAgainstAMaxDownloadsOfOneMaterialAllowExactlyOneSuccess() throws Exception {
		Fixture f = seedFixture("mtype-download-race");
		MaterialResponse material = createFileMaterialOrFail(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Single-Download Handout", MaterialType.PDF, pdfFile("race.pdf"), 1, null, null);

		int threadCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		List<HttpStatus> statuses = new CopyOnWriteArrayList<>();
		try {
			List<Runnable> tasks = IntStream.range(0, threadCount)
				.<Runnable>mapToObj(i -> () -> {
					ready.countDown();
					try {
						start.await();
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(f.host(), f.teacherToken(),
							f.courseId(), f.moduleId(), f.lessonId(), material.id());
					statuses.add(result.getStatusCode());
				})
				.toList();
			tasks.forEach(executor::execute);
			ready.await(5, TimeUnit.SECONDS);
			start.countDown();
		}
		finally {
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(statuses).hasSize(threadCount);
		assertThat(statuses.stream().filter(HttpStatus.OK::equals).count()).as("exactly one request succeeds")
			.isEqualTo(1);
		assertThat(statuses.stream().filter(HttpStatus.FORBIDDEN::equals).count())
			.isEqualTo(threadCount - 1);
	}

	// ------------------------------------------------------------------
	// Availability window (plan §8).
	// ------------------------------------------------------------------

	@Test
	void downloadUrlBeforeAvailableFromAtIsBlockedWith403MaterialNotYetAvailable() {
		Fixture f = seedFixture("mtype-not-yet-available");
		String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();
		MaterialResponse material = createFileMaterialOrFail(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Future Handout", MaterialType.PDF, pdfFile("future.pdf"), null, future, null);

		HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.MATERIAL_NOT_YET_AVAILABLE);
	}

	@Test
	void downloadUrlAfterExpiryAtIsBlockedWith403MaterialExpired() {
		Fixture f = seedFixture("mtype-expired");
		String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
		MaterialResponse material = createFileMaterialOrFail(f.host(), f.teacherToken(), f.courseId(), f.moduleId(),
				f.lessonId(), "Expired Handout", MaterialType.PDF, pdfFile("expired.pdf"), null, null, past);

		HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(f.host(), f.teacherToken(), f.courseId(),
				f.moduleId(), f.lessonId(), material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.MATERIAL_EXPIRED);
	}

}
