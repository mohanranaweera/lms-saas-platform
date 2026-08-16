package com.lms.coursemanagement.course.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.AbstractIntegrationTest;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Direct repository-level proof that V11's composite FKs {@code
 * fk_course_module_course} ({@code FOREIGN KEY (tenant_id, course_id)
 * REFERENCES course (tenant_id, id)}) and {@code fk_course_lesson_module}
 * ({@code FOREIGN KEY (tenant_id, module_id) REFERENCES course_module
 * (tenant_id, id)}) are genuine schema-level backstops for course-structure
 * tenant isolation - mirrors {@link CourseTeacherCompositeFkIntegrationTest}'s
 * established pattern exactly (JPA-save attempt AND raw-SQL insert, both
 * asserted independently), closing the module review's Medium finding that
 * this composite-FK pair had no dedicated integration-test coverage of its
 * own (only the teacher FK did).
 */
class CourseStructureCompositeFkIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private CourseRepository courseRepository;

	@Autowired
	private CourseModuleRepository courseModuleRepository;

	@Autowired
	private CourseLessonRepository courseLessonRepository;

	@Autowired
	private TenantRepository tenantRepository;

	@Autowired
	private TenantUserRepository tenantUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// ------------------------------------------------------------------
	// course_module.tenant_id must match its parent course's tenant_id.
	// ------------------------------------------------------------------

	@Test
	void persistingACourseModuleWithATenantIdThatDoesNotMatchItsParentCoursesTenantViolatesTheCompositeForeignKey() {
		Tenant tenantA = seedTenant("fk-module-a");
		Tenant tenantB = seedTenant("fk-module-b");
		Course courseInTenantA = createCourse(tenantA);

		// Bypasses TenantAwareRepositoryImpl's own save-time tenant guard by
		// switching TenantContext to B only for the module's persistence -
		// the point of this test is the *database* constraint, not the
		// application-layer guard that would normally catch this first.
		CourseModule moduleWithMismatchedTenant = new CourseModule(tenantB.getId(), courseInTenantA.getId(),
				"Cross Tenant Module", 1);

		assertThatThrownBy(
				() -> withTenant(tenantB.getId(), () -> courseModuleRepository.saveAndFlush(moduleWithMismatchedTenant)))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course_module WHERE course_id = ?", Long.class,
				courseInTenantA.getId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void persistingACourseModuleWithTheSameTenantAsItsParentCourseSucceeds() {
		Tenant tenant = seedTenant("fk-module-ok");
		Course course = createCourse(tenant);

		CourseModule module = new CourseModule(tenant.getId(), course.getId(), "Same Tenant Module", 1);

		CourseModule saved = withTenant(tenant.getId(), () -> courseModuleRepository.saveAndFlush(module));

		assertThat(saved.getId()).isNotNull();
	}

	@Test
	void rawSqlInsertOfACourseModuleWithATenantIdThatDoesNotMatchItsParentCoursesTenantIsRejectedByTheDatabase() {
		Tenant tenantA = seedTenant("fk-module-raw-a");
		Tenant tenantB = seedTenant("fk-module-raw-b");
		Course courseInTenantA = createCourse(tenantA);

		assertThatThrownBy(() -> insertCourseModule(tenantB.getId(), courseInTenantA.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// course_lesson.tenant_id must match its parent course_module's tenant_id.
	// ------------------------------------------------------------------

	@Test
	void persistingACourseLessonWithATenantIdThatDoesNotMatchItsParentModulesTenantViolatesTheCompositeForeignKey() {
		Tenant tenantA = seedTenant("fk-lesson-a");
		Tenant tenantB = seedTenant("fk-lesson-b");
		Course courseInTenantA = createCourse(tenantA);
		CourseModule moduleInTenantA = createModule(tenantA, courseInTenantA);

		// Same bypass rationale as the course_module JPA-save test above: the
		// point here is the *database* constraint, proven independently of
		// TenantAwareRepositoryImpl's own save-time tenant guard.
		CourseLesson lessonWithMismatchedTenant = new CourseLesson(tenantB.getId(), moduleInTenantA.getId(),
				"Cross Tenant Lesson", 1);

		assertThatThrownBy(
				() -> withTenant(tenantB.getId(), () -> courseLessonRepository.saveAndFlush(lessonWithMismatchedTenant)))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course_lesson WHERE module_id = ?", Long.class,
				moduleInTenantA.getId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void persistingACourseLessonWithTheSameTenantAsItsParentModuleSucceeds() {
		Tenant tenant = seedTenant("fk-lesson-ok");
		Course course = createCourse(tenant);
		CourseModule module = createModule(tenant, course);

		CourseLesson lesson = new CourseLesson(tenant.getId(), module.getId(), "Same Tenant Lesson", 1);

		CourseLesson saved = withTenant(tenant.getId(), () -> courseLessonRepository.saveAndFlush(lesson));

		assertThat(saved.getId()).isNotNull();
	}

	@Test
	void rawSqlInsertOfACourseLessonWithATenantIdThatDoesNotMatchItsParentModulesTenantIsRejectedByTheDatabase() {
		Tenant tenantA = seedTenant("fk-lesson-raw-a");
		Tenant tenantB = seedTenant("fk-lesson-raw-b");
		Course courseInTenantA = createCourse(tenantA);
		CourseModule moduleInTenantA = createModule(tenantA, courseInTenantA);

		assertThatThrownBy(() -> insertCourseLesson(tenantB.getId(), moduleInTenantA.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// Fixtures.
	// ------------------------------------------------------------------

	private Course createCourse(Tenant tenant) {
		TenantUser teacher = withTenant(tenant.getId(),
				() -> tenantUserRepository.save(new TenantUser(tenant.getId(), "teacher-" + UUID.randomUUID()
						+ "@example.test", passwordEncoder.encode("irrelevant-password"), Role.TEACHER)));
		Course course = new Course(tenant.getId(), teacher.getId(), "Fixture Course", "fixture-" + UUID.randomUUID(),
				"Math", null, null, null, null, null, new BigDecimal("10.00"), null, null, CourseStatus.DRAFT);
		return withTenant(tenant.getId(), () -> courseRepository.saveAndFlush(course));
	}

	private CourseModule createModule(Tenant tenant, Course course) {
		CourseModule module = new CourseModule(tenant.getId(), course.getId(), "Fixture Module", 1);
		return withTenant(tenant.getId(), () -> courseModuleRepository.saveAndFlush(module));
	}

	private void insertCourseModule(UUID tenantId, UUID courseId) {
		jdbcTemplate.update(
				"INSERT INTO course_module (id, tenant_id, course_id, title, sequence, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, ?, now(), now())",
				UUID.randomUUID(), tenantId, courseId, "Raw SQL Cross Tenant Module", 1);
	}

	private void insertCourseLesson(UUID tenantId, UUID moduleId) {
		jdbcTemplate.update(
				"INSERT INTO course_lesson (id, tenant_id, module_id, title, sequence, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, ?, now(), now())",
				UUID.randomUUID(), tenantId, moduleId, "Raw SQL Cross Tenant Lesson", 1);
	}

	private Tenant seedTenant(String prefix) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant (id, name, subdomain, status, requested_plan, contact_name, contact_email, "
						+ "contact_phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
				id, "Test Institute " + prefix, prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
				TenantStatus.ACTIVE.toColumnValue(), "starter", "Test Contact", "contact-" + id + "@example.test",
				"+1-555-0100");
		return tenantRepository.findById(id).orElseThrow();
	}

}
