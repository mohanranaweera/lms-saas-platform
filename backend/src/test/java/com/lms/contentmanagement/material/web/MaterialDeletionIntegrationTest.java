package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.contentmanagement.material.service.MaterialService;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testcontainers-backed HTTP coverage for material delete (MVP-009):
 * happy-path deletion, and the cross-tenant/non-owner/Student negative cases,
 * each proving the material is still present afterward when it should be.
 */
class MaterialDeletionIntegrationTest extends ContentManagementTestSupport {

	@Autowired
	private InMemoryObjectStorageApi objectStorage;

	@Autowired
	private PlatformTransactionManager platformTransactionManager;

	@Autowired
	private MaterialService materialService;

	@Autowired
	private MaterialRepository materialRepository;

	@AfterEach
	void clearManuallySetPrincipal() {
		// H2/M5 below drive MaterialService directly (outside the normal
		// JwtAuthenticationFilter request path) via AuthenticatedPrincipalHolder
		// .set(...) - clear it so no principal leaks onto a pooled test thread's
		// next use, mirroring AbstractIntegrationTest's own TenantContextHolder
		// discipline.
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void owningTeacherDeletesAMaterialAndItDisappearsFromTheList() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("delete-owner"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug("delete-owner"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"To Delete", pdfFile("to-delete.pdf"));

		HttpResult<Void> result = deleteMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).extracting(MaterialResponse::id).doesNotContain(material.id());
	}

	@Test
	void crossTenantDeleteAttemptReturns404AndTheMaterialIsStillPresentForItsRealOwner() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("delete-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("delete-cross-b"));
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("delete-cross"), null));
		CourseModuleResponse moduleA = createModuleOrFail(hostA, tokenA, courseA.id(), "Module 1", 1);
		var lessonA = createLessonOrFail(hostA, tokenA, courseA.id(), moduleA.id(), "Lesson 1", 1);
		MaterialResponse materialA = createMaterialOrFail(hostA, tokenA, courseA.id(), moduleA.id(), lessonA.id(),
				"Tenant A Material", pdfFile("tenant-a.pdf"));

		HttpResult<Void> result = deleteMaterial(hostB, tokenB, courseA.id(), moduleA.id(), lessonA.id(),
				materialA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<List<MaterialResponse>> listed = listMaterials(hostA, tokenA, courseA.id(), moduleA.id(),
				lessonA.id());
		assertThat(listed.getBody().data()).extracting(MaterialResponse::id).contains(materialA.id());
	}

	@Test
	void nonOwningTeacherInTheSameTenantDeleteAttemptReturns403AndTheMaterialIsStillPresent() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("delete-non-owner"));
		seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBToken = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse course = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("delete-non-owner"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherAToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherAToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherAToken, course.id(), module.id(), lesson.id(),
				"Owned By A", pdfFile("owned.pdf"));

		HttpResult<Void> result = deleteMaterial(host, teacherBToken, course.id(), module.id(), lesson.id(),
				material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherAToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).extracting(MaterialResponse::id).contains(material.id());
	}

	/**
	 * Per {@code MaterialAccessGuard}'s anti-enumeration rule, a Student
	 * caller on the DELETE action (non-VIEW) is denied with {@code
	 * NotFoundException} - see {@code MaterialUploadIntegrationTest}'s
	 * equivalent-flagged upload test for the same rule. A naive "Students
	 * are forbidden -> 403" expectation would be wrong here; the actual,
	 * correct behavior is 404.
	 */
	@Test
	void studentDeleteAttemptReturns404PerTheAntiEnumerationRuleAndTheMaterialIsStillPresent() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("delete-student"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("delete-student"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Protected", pdfFile("protected.pdf"));

		HttpResult<Void> result = deleteMaterial(host, studentToken, course.id(), module.id(), lesson.id(),
				material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).extracting(MaterialResponse::id).contains(material.id());
	}

	// ------------------------------------------------------------------
	// H1 - onMaterialDeleted's @TransactionalEventListener(AFTER_COMMIT)
	// must actually fire against a REAL committed HTTP-driven transaction,
	// not just be directly-invokable (the pre-existing MaterialServiceTest
	// coverage bypasses Spring's event/transaction machinery entirely).
	// ------------------------------------------------------------------

	@Test
	void deletingAMaterialOverHttpGenuinelyRemovesItsObjectFromStorageProvingTheAfterCommitListenerFired() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("delete-storage-cleanup"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("delete-storage-cleanup"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		// MaterialResponse deliberately never exposes storageObjectKey (protected
		// -content security design) - recover the real generated key by diffing
		// the in-memory store's key set immediately around the upload call.
		Set<String> keysBeforeUpload = objectStorage.keySet();
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"To Delete", pdfFile("storage-cleanup.pdf"));
		Set<String> keysAfterUpload = new HashSet<>(objectStorage.keySet());
		keysAfterUpload.removeAll(keysBeforeUpload);
		assertThat(keysAfterUpload).as("exactly one new object key from this upload").hasSize(1);
		String objectKey = keysAfterUpload.iterator().next();
		assertThat(objectStorage.containsKey(objectKey)).isTrue();

		HttpResult<Void> result = deleteMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		// MockMvc executes the whole request (including commit + AFTER_COMMIT
		// listeners) synchronously on the calling thread, so by the time the
		// HTTP response has returned, onMaterialDeleted must already have run.
		assertThat(objectStorage.containsKey(objectKey)).isFalse();
	}

	// ------------------------------------------------------------------
	// M5 - a MaterialDeletedEvent must never survive (i.e. never fire its
	// AFTER_COMMIT listener for) a transaction that rolls back. Driven via
	// TransactionTemplate rather than the test method's own @Transactional,
	// since MockMvc-driven HTTP calls run in their own separately-committed
	// transaction that a test-method-level @Transactional would never share.
	// ------------------------------------------------------------------

	@Test
	void aMaterialDeletedEventNeverFiresItsStorageCleanupWhenTheEnclosingTransactionRollsBack() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("delete-rollback"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("delete-rollback"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		Set<String> keysBeforeUpload = objectStorage.keySet();
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Rollback Target", pdfFile("rollback.pdf"));
		Set<String> keysAfterUpload = new HashSet<>(objectStorage.keySet());
		keysAfterUpload.removeAll(keysBeforeUpload);
		String objectKey = keysAfterUpload.iterator().next();

		UUID courseId = course.id();
		UUID moduleId = module.id();
		UUID lessonId = lesson.id();
		UUID materialId = material.id();
		TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);
		// deleteMaterial's own guard/repository calls still need a resolved
		// tenant context + authenticated principal even though this bypasses
		// the normal JwtAuthenticationFilter/TenantResolutionFilter HTTP path -
		// set both explicitly, matching the real teacher who owns this lesson.
		TenantContextHolder.set(tenant.getId());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(teacher.getId(), tenant.getId(), "TEACHER",
				UUID.randomUUID()));
		try {
			transactionTemplate.execute(new TransactionCallbackWithoutResult() {

				@Override
				protected void doInTransactionWithoutResult(TransactionStatus status) {
					materialService.deleteMaterial(courseId, moduleId, lessonId, materialId);
					status.setRollbackOnly();
				}
			});
		}
		finally {
			TenantContextHolder.clear();
		}

		// The DB effect rolled back...
		withTenant(tenant.getId(),
				() -> assertThat(materialRepository.findByIdAndLessonId(materialId, lessonId)).isPresent());
		// ...and, because the transaction never actually committed, the
		// AFTER_COMMIT listener never ran, so the storage object is untouched.
		assertThat(objectStorage.containsKey(objectKey)).isTrue();
	}

}
