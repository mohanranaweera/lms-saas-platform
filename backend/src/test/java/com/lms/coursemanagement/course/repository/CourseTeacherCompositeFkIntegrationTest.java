package com.lms.coursemanagement.course.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.AbstractIntegrationTest;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.coursemanagement.course.domain.Course;
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
 * Direct repository-level proof that V11's composite {@code fk_course_teacher}
 * ({@code FOREIGN KEY (tenant_id, teacher_id) REFERENCES tenant_user
 * (tenant_id, id)}) is a genuine schema-level backstop for teacher-assignment
 * tenant isolation - independent of, and deliberately bypassing, {@code
 * CourseService#validateTeacherCandidate}'s service-layer check (plan
 * §14/§15: "enforced as a composite-FK constraint violation, not merely a
 * service-layer check"). Mirrors {@code
 * identityaccessservice.repository.RoleCatalogRepositoryIntegrationTest}'s
 * established technique for proving a composite/catalog FK is real.
 */
class CourseTeacherCompositeFkIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private CourseRepository courseRepository;

	@Autowired
	private TenantRepository tenantRepository;

	@Autowired
	private TenantUserRepository tenantUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void persistingACourseWithATeacherIdFromADifferentTenantViolatesTheCompositeForeignKey() {
		Tenant tenantA = seedTenant("fk-course-a");
		Tenant tenantB = seedTenant("fk-course-b");
		TenantUser teacherInTenantB = withTenant(tenantB.getId(),
				() -> tenantUserRepository.save(new TenantUser(tenantB.getId(), "teacher-b@example.test",
						passwordEncoder.encode("irrelevant-password"), Role.TEACHER)));

		Course course = new Course(tenantA.getId(), teacherInTenantB.getId(), "Cross Tenant Teacher Course",
				"fk-cross-tenant-" + UUID.randomUUID(), "Math", null, null, null, null, null, new BigDecimal("10.00"),
				null, null, CourseStatus.DRAFT);

		TenantContextHolder.set(tenantA.getId());
		try {
			assertThatThrownBy(() -> courseRepository.saveAndFlush(course))
				.isInstanceOf(DataIntegrityViolationException.class);
		}
		finally {
			TenantContextHolder.clear();
		}

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE tenant_id = ?", Long.class,
				tenantA.getId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void persistingACourseWithATeacherIdFromTheSameTenantSucceeds() {
		Tenant tenant = seedTenant("fk-course-ok");
		TenantUser teacher = withTenant(tenant.getId(),
				() -> tenantUserRepository.save(new TenantUser(tenant.getId(), "teacher-ok@example.test",
						passwordEncoder.encode("irrelevant-password"), Role.TEACHER)));

		Course course = new Course(tenant.getId(), teacher.getId(), "Same Tenant Teacher Course",
				"fk-same-tenant-" + UUID.randomUUID(), "Math", null, null, null, null, null, new BigDecimal("10.00"),
				null, null, CourseStatus.DRAFT);

		TenantContextHolder.set(tenant.getId());
		Course saved;
		try {
			saved = courseRepository.saveAndFlush(course);
		}
		finally {
			TenantContextHolder.clear();
		}

		assertThat(saved.getId()).isNotNull();
	}

	/**
	 * Same invariant as above, proven via a raw SQL insert (bypassing JPA
	 * entirely too), matching {@code RoleCatalogRepositoryIntegrationTest}'s
	 * established pattern for extra confidence that this is a real database
	 * constraint, not an artifact of Hibernate's own validation.
	 */
	@Test
	void rawSqlInsertOfACourseWithATeacherIdFromADifferentTenantIsRejectedByTheDatabase() {
		Tenant tenantA = seedTenant("fk-course-raw-a");
		Tenant tenantB = seedTenant("fk-course-raw-b");
		TenantUser teacherInTenantB = withTenant(tenantB.getId(),
				() -> tenantUserRepository.save(new TenantUser(tenantB.getId(), "teacher-raw-b@example.test",
						passwordEncoder.encode("irrelevant-password"), Role.TEACHER)));

		assertThatThrownBy(() -> insertCourse(tenantA.getId(), teacherInTenantB.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertCourse(UUID tenantId, UUID teacherId) {
		jdbcTemplate.update(
				"INSERT INTO course (id, tenant_id, teacher_id, name, slug, category, price, status, created_at, "
						+ "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', now(), now())",
				UUID.randomUUID(), tenantId, teacherId, "Raw SQL Cross Tenant Course",
				"fk-raw-cross-tenant-" + UUID.randomUUID(), "Math", new BigDecimal("10.00"));
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
