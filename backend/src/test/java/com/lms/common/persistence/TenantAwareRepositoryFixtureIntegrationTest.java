package com.lms.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.AbstractIntegrationTest;
import com.lms.common.tenant.TenantContextNotResolvedException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the ADR-006 structural tenant-filtering mechanism against a real
 * Postgres (Testcontainers), not a mock - a mocked repository cannot fail a
 * missing tenant_id filter. This is the platform's single highest-security
 * -value mechanism; kept permanently as a regression guard even after a
 * real domain module ships its own tenant-owned tables.
 *
 * {@code @Transactional} rolls back every test's writes/seeded rows so
 * fixture data never leaks between test methods.
 */
@Transactional
class TenantAwareRepositoryFixtureIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private TestFixtureWidgetRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void readingTenantContextBeforeItIsSetFailsLoudly() {
		assertThatThrownBy(() -> repository.findAll()).isInstanceOf(TenantContextNotResolvedException.class);
	}

	@Test
	void findAllOnlyReturnsRowsForCurrentTenant() {
		seedWidget(TENANT_A, "tenant-a-widget");
		seedWidget(TENANT_B, "tenant-b-widget");

		var tenantAWidgets = withTenant(TENANT_A, () -> repository.findAll());

		assertThat(tenantAWidgets).extracting(TestFixtureWidget::getName).containsExactly("tenant-a-widget");
	}

	@Test
	void findByIdDoesNotLeakAnotherTenantsRowAsNotFound() {
		UUID tenantBWidgetId = seedWidget(TENANT_B, "tenant-b-secret");

		var result = withTenant(TENANT_A, () -> repository.findById(tenantBWidgetId));

		assertThat(result).isEmpty();
	}

	@Test
	void countIsScopedToCurrentTenant() {
		seedWidget(TENANT_A, "a-1");
		seedWidget(TENANT_A, "a-2");
		seedWidget(TENANT_B, "b-1");

		long tenantACount = withTenant(TENANT_A, () -> repository.count());

		assertThat(tenantACount).isEqualTo(2);
	}

	@Test
	void saveStampsAuditFieldsAndPersistsUnderCurrentTenant() {
		TestFixtureWidget saved = withTenant(TENANT_A, () -> repository.save(new TestFixtureWidget(TENANT_A, "new-widget")));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();

		var reloaded = withTenant(TENANT_A, () -> repository.findById(saved.getId()));
		assertThat(reloaded).isPresent();
	}

	@Test
	void savingAnEntityForAnotherTenantThanTheCurrentContextIsRejected() {
		TestFixtureWidget wrongTenantWidget = new TestFixtureWidget(TENANT_B, "mismatched");

		assertThatThrownBy(() -> withTenant(TENANT_A, () -> repository.save(wrongTenantWidget)))
			.isInstanceOf(CrossTenantPersistenceException.class);
	}

	@Test
	void getReferenceByIdThrowsUnsupportedOperationException() {
		UUID tenantAWidgetId = seedWidget(TENANT_A, "tenant-a-widget");

		assertThatThrownBy(() -> withTenant(TENANT_A, () -> repository.getReferenceById(tenantAWidgetId)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void deleteAllInBatchOnlyDeletesCurrentTenantsRows() {
		seedWidget(TENANT_A, "tenant-a-widget");
		UUID tenantBWidgetId = seedWidget(TENANT_B, "tenant-b-widget");

		withTenant(TENANT_A, () -> repository.deleteAllInBatch());

		assertThat(withTenant(TENANT_A, () -> repository.count())).isZero();
		Long tenantBCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM test_fixture_widget WHERE id = ?", Long.class, tenantBWidgetId);
		assertThat(tenantBCount).isEqualTo(1L);
	}

	@Test
	void deleteAllInBatchWithEntitiesRejectsCrossTenantEntity() {
		UUID tenantAWidgetId = seedWidget(TENANT_A, "tenant-a-widget");
		TestFixtureWidget tenantAWidget = withTenant(TENANT_A, () -> repository.findById(tenantAWidgetId)).orElseThrow();

		assertThatThrownBy(() -> withTenant(TENANT_B, () -> {
			repository.deleteAllInBatch(List.of(tenantAWidget));
			return null;
		})).isInstanceOf(CrossTenantPersistenceException.class);
	}

	@Test
	void deleteAllByIdInBatchSilentlyExcludesOtherTenantsIds() {
		UUID tenantAWidgetId = seedWidget(TENANT_A, "tenant-a-widget");
		UUID tenantBWidgetId = seedWidget(TENANT_B, "tenant-b-widget");

		withTenant(TENANT_A, () -> repository.deleteAllByIdInBatch(List.of(tenantAWidgetId, tenantBWidgetId)));

		Long tenantACount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM test_fixture_widget WHERE id = ?", Long.class, tenantAWidgetId);
		assertThat(tenantACount).isEqualTo(0L);
		Long tenantBCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM test_fixture_widget WHERE id = ?", Long.class, tenantBWidgetId);
		assertThat(tenantBCount).isEqualTo(1L);
	}

	private UUID seedWidget(UUID tenantId, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO test_fixture_widget (id, tenant_id, name, created_at, updated_at) "
						+ "VALUES (?, ?, ?, now(), now())",
				id, tenantId, name);
		return id;
	}

}
