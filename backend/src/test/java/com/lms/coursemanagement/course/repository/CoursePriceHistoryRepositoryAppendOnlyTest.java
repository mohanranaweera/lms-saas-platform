package com.lms.coursemanagement.course.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.AbstractIntegrationTest;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.coursemanagement.course.domain.CoursePriceHistory;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves all eight delete-shaped methods {@link CoursePriceHistoryRepository}
 * inherits from {@code TenantAwareRepository}/{@code JpaRepository} -
 * {@code deleteById}, {@code delete}, {@code deleteAllById}, {@code
 * deleteAll(Iterable)}, {@code deleteAll()}, {@code deleteAllInBatch()},
 * {@code deleteAllInBatch(Iterable)}, {@code deleteAllByIdInBatch(Iterable)}
 * - throw {@link UnsupportedOperationException} rather than silently
 * deleting an append-only, financial/audit-adjacent row (per root {@code
 * CLAUDE.md}'s "never delete financial history" and {@code
 * .claude/rules/backend.md}'s append-only enforcement guidance). None of
 * these eight had prior test coverage - the module review flagged that the
 * three batch-delete methods specifically fell through to {@code
 * TenantAwareRepositoryImpl}'s real bulk-delete implementation, defeating
 * the append-only guarantee the other five overrides already enforced.
 *
 * <p>Mirrors {@code CourseTeacherCompositeFkIntegrationTest}'s inline raw
 * -SQL tenant/course seeding convention.
 */
class CoursePriceHistoryRepositoryAppendOnlyTest extends AbstractIntegrationTest {

	@Autowired
	private CoursePriceHistoryRepository coursePriceHistoryRepository;

	@Autowired
	private TenantRepository tenantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void deleteByIdIsRejected() {
		CoursePriceHistory row = seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteById(row.getId()))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteEntityIsRejected() {
		CoursePriceHistory row = seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.delete(row))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllByIdIsRejected() {
		CoursePriceHistory row = seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteAllById(List.of(row.getId())))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllIterableIsRejected() {
		CoursePriceHistory row = seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteAll(List.of(row)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllIsRejected() {
		seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteAll())
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllInBatchNoArgsIsRejected() {
		seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllInBatchIterableIsRejected() {
		CoursePriceHistory row = seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteAllInBatch(List.of(row)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllByIdInBatchIsRejected() {
		CoursePriceHistory row = seedRow();
		assertThatThrownBy(() -> coursePriceHistoryRepository.deleteAllByIdInBatch(List.of(row.getId())))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	/**
	 * Seeds a real, persisted {@code course_price_history} row (via raw SQL,
	 * independent of {@code CourseService#changePrice}) so every delete
	 * -shaped method above is proven to reject deletion of an actual row -
	 * not merely a nonexistent id, which would be a weaker proof.
	 */
	private CoursePriceHistory seedRow() {
		Tenant tenant = seedTenant("price-history-append-only");
		UUID courseId = UUID.randomUUID();
		UUID changedBy = UUID.randomUUID();
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant_user (id, tenant_id, email, password_hash, role, status, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, 'TEACHER', 'active', now(), now())",
				changedBy, tenant.getId(), "changed-by-" + id + "@example.test", "irrelevant-hash");
		jdbcTemplate.update(
				"INSERT INTO course (id, tenant_id, teacher_id, name, slug, category, price, status, created_at, "
						+ "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', now(), now())",
				courseId, tenant.getId(), changedBy, "Append Only Fixture Course", "append-only-" + id, "Math",
				new BigDecimal("10.00"));
		jdbcTemplate.update(
				"INSERT INTO course_price_history (id, tenant_id, course_id, changed_by, previous_price, new_price, "
						+ "created_at) VALUES (?, ?, ?, ?, ?, ?, now())",
				id, tenant.getId(), courseId, changedBy, new BigDecimal("10.00"), new BigDecimal("20.00"));

		TenantContextHolder.set(tenant.getId());
		try {
			return coursePriceHistoryRepository.findById(id).orElseThrow();
		}
		finally {
			TenantContextHolder.clear();
		}
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
