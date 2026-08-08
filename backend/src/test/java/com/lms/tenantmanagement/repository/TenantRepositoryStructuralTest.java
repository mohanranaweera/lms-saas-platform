package com.lms.tenantmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.persistence.TenantOwned;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;

/**
 * Structural regression guard (plan {@code MVP-004 Tenant Management.md} SS9
 * / SS14): {@code tenant} is the platform's root identity aggregate, not
 * tenant-owned, so its repository must stay a plain {@code JpaRepository}
 * and its entity must never implement {@link TenantOwned}. A future PR
 * "helpfully" making either of these tenant-aware would be a category error
 * - see {@link Tenant}'s and {@link TenantRepository}'s own doc comments for
 * the full rationale. Plain JUnit, no Spring context/Testcontainers needed:
 * this is pure reflection over the compiled type hierarchy.
 */
class TenantRepositoryStructuralTest {

	@Test
	void tenantRepositoryDoesNotExtendTenantAwareRepository() {
		assertThat(TenantAwareRepository.class.isAssignableFrom(TenantRepository.class)).isFalse();
	}

	@Test
	void tenantEntityDoesNotImplementTenantOwned() {
		assertThat(TenantOwned.class.isAssignableFrom(Tenant.class)).isFalse();
	}

}
