package com.lms.contentmanagement.material.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.AbstractIntegrationTest;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
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
 * Direct repository-level proof that V16's composite FK {@code
 * fk_material_lesson} ({@code FOREIGN KEY (tenant_id, lesson_id) REFERENCES
 * course_lesson (tenant_id, id)}) is a genuine schema-level backstop for
 * material tenant isolation - mirrors {@code
 * CourseStructureCompositeFkIntegrationTest}'s established pattern exactly
 * (JPA-save attempt AND raw-SQL insert, both asserted independently).
 */
class MaterialCompositeFkIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MaterialRepository materialRepository;

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

	@Test
	void persistingAMaterialWithATenantIdThatDoesNotMatchItsParentLessonsTenantViolatesTheCompositeForeignKey() {
		Tenant tenantA = seedTenant("fk-material-a");
		Tenant tenantB = seedTenant("fk-material-b");
		CourseLesson lessonInTenantA = createLesson(tenantA);
		TenantUser uploaderInTenantB = createTeacher(tenantB);

		// Bypasses TenantAwareRepositoryImpl's own save-time tenant guard by
		// switching TenantContext to B only for the material's persistence -
		// the point of this test is the *database* constraint, not the
		// application-layer guard that would normally catch this first.
		Material materialWithMismatchedTenant = new Material(tenantB.getId(), lessonInTenantA.getId(),
				"Cross Tenant Material", "notes.pdf", "storage-key-" + UUID.randomUUID(), "application/pdf", 1024L, 1,
				uploaderInTenantB.getId());

		assertThatThrownBy(() -> withTenant(tenantB.getId(),
				() -> materialRepository.saveAndFlush(materialWithMismatchedTenant)))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM material WHERE lesson_id = ?", Long.class,
				lessonInTenantA.getId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void persistingAMaterialWithTheSameTenantAsItsParentLessonSucceeds() {
		Tenant tenant = seedTenant("fk-material-ok");
		CourseLesson lesson = createLesson(tenant);
		TenantUser uploader = createTeacher(tenant);

		Material material = new Material(tenant.getId(), lesson.getId(), "Same Tenant Material", "notes.pdf",
				"storage-key-" + UUID.randomUUID(), "application/pdf", 1024L, 1, uploader.getId());

		Material saved = withTenant(tenant.getId(), () -> materialRepository.saveAndFlush(material));

		assertThat(saved.getId()).isNotNull();
	}

	@Test
	void rawSqlInsertOfAMaterialWithATenantIdThatDoesNotMatchItsParentLessonsTenantIsRejectedByTheDatabase() {
		Tenant tenantA = seedTenant("fk-material-raw-a");
		Tenant tenantB = seedTenant("fk-material-raw-b");
		CourseLesson lessonInTenantA = createLesson(tenantA);
		TenantUser uploaderInTenantB = createTeacher(tenantB);

		assertThatThrownBy(() -> insertMaterial(tenantB.getId(), lessonInTenantA.getId(), uploaderInTenantB.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// Fixtures.
	// ------------------------------------------------------------------

	private CourseLesson createLesson(Tenant tenant) {
		TenantUser teacher = createTeacher(tenant);
		Course course = new Course(tenant.getId(), teacher.getId(), "Fixture Course", "fixture-" + UUID.randomUUID(),
				"Math", null, null, null, null, null, new BigDecimal("10.00"), null, null, CourseStatus.DRAFT);
		Course savedCourse = withTenant(tenant.getId(), () -> courseRepository.saveAndFlush(course));
		CourseModule module = new CourseModule(tenant.getId(), savedCourse.getId(), "Fixture Module", 1);
		CourseModule savedModule = withTenant(tenant.getId(), () -> courseModuleRepository.saveAndFlush(module));
		CourseLesson lesson = new CourseLesson(tenant.getId(), savedModule.getId(), "Fixture Lesson", 1);
		return withTenant(tenant.getId(), () -> courseLessonRepository.saveAndFlush(lesson));
	}

	private TenantUser createTeacher(Tenant tenant) {
		return withTenant(tenant.getId(),
				() -> tenantUserRepository.save(new TenantUser(tenant.getId(),
						"teacher-" + UUID.randomUUID() + "@example.test",
						passwordEncoder.encode("irrelevant-password"), Role.TEACHER)));
	}

	private void insertMaterial(UUID tenantId, UUID lessonId, UUID uploadedBy) {
		jdbcTemplate.update(
				"INSERT INTO material (id, tenant_id, lesson_id, title, original_filename, storage_object_key, "
						+ "mime_type, size_bytes, sequence, visibility, uploaded_by, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
				UUID.randomUUID(), tenantId, lessonId, "Raw SQL Cross Tenant Material", "notes.pdf",
				"storage-key-" + UUID.randomUUID(), "application/pdf", 1024L, 1, "VISIBLE", uploadedBy);
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
