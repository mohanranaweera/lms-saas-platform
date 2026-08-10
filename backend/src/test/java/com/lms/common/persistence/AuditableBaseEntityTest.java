package com.lms.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.AbstractIntegrationTest;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standalone regression guard for {@link Auditable}/{@link BaseEntity} audit
 * field behavior, independent of the broader tenant-isolation assertions in
 * {@link TenantAwareRepositoryFixtureIntegrationTest}. Covers both halves of
 * {@link AuditorAwareImpl}'s contract: {@code createdBy}/{@code updatedBy}
 * stay null for a write with no authenticated actor on the thread (the
 * common case for this test class, and for background/system writes in
 * general), and get stamped with the real actor id when {@link
 * AuthenticatedPrincipalHolder} is populated (mirroring what {@code
 * JwtAuthenticationFilter} does for a real authenticated request).
 *
 * {@code @Transactional} rolls back all writes so fixture data never leaks
 * between test methods or other test classes sharing the same Testcontainers
 * -backed context.
 */
@Transactional
class AuditableBaseEntityTest extends AbstractIntegrationTest {

	@Autowired
	private TestFixtureWidgetRepository repository;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void auditFieldsAreStampedOnCreateAndUpdatedOnSubsequentSave() throws Exception {
		TestFixtureWidget saved = withTenant(TENANT_A,
				() -> repository.save(new TestFixtureWidget(TENANT_A, "audit-widget")));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
		assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());
		assertThat(saved.getCreatedBy()).isNull();
		assertThat(saved.getUpdatedBy()).isNull();

		var firstCreatedAt = saved.getCreatedAt();
		var firstUpdatedAt = saved.getUpdatedAt();

		Thread.sleep(20);
		setName(saved, "audit-widget-renamed");

		withTenant(TENANT_A, () -> {
			repository.save(saved);
			entityManager.flush();
			return null;
		});

		assertThat(saved.getCreatedAt()).isEqualTo(firstCreatedAt);
		assertThat(saved.getUpdatedAt()).isAfter(firstUpdatedAt);
		assertThat(saved.getCreatedBy()).isNull();
		assertThat(saved.getUpdatedBy()).isNull();
	}

	@Test
	void auditFieldsAreStampedWithTheAuthenticatedActorWhenOneIsPresent() {
		UUID actorId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(actorId, TENANT_A, "TENANT_ADMIN", UUID.randomUUID()));
		try {
			TestFixtureWidget saved = withTenant(TENANT_A,
					() -> repository.save(new TestFixtureWidget(TENANT_A, "audited-widget")));

			assertThat(saved.getCreatedBy()).isEqualTo(actorId);
			assertThat(saved.getUpdatedBy()).isEqualTo(actorId);
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
		}
	}

	private static void setName(TestFixtureWidget widget, String name) throws Exception {
		Field nameField = TestFixtureWidget.class.getDeclaredField("name");
		nameField.setAccessible(true);
		nameField.set(widget, name);
	}

}
