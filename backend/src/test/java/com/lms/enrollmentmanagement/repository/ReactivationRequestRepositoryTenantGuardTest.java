package com.lms.enrollmentmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Defense-in-depth coverage (post-ship review, hardening item per plan
 * MVP-021 Sec.9.1/14/18.3) for the tenant-id guard added to {@link
 * ReactivationRequestRepository#findByIdAndTenantIdForUpdate} - the only
 * method on this repository that takes {@code tenantId} as an explicit
 * parameter instead of relying on {@code TenantAwareRepositoryImpl}'s
 * structural {@code Specification} filtering (see that method's javadoc).
 *
 * <p>Mirrors {@code AttendanceRecordRepositoryTenantGuardTest}'s exact shape:
 * no Spring context or database is needed - the interface's real {@code
 * default} method is exercised via {@link
 * org.mockito.Mockito#CALLS_REAL_METHODS} while the underlying {@code
 * @Query}-backed {@code *Unchecked} method is stubbed.
 */
@Tag("cross-tenant")
class ReactivationRequestRepositoryTenantGuardTest {

	private final ReactivationRequestRepository repository = mock(ReactivationRequestRepository.class,
			CALLS_REAL_METHODS);

	@AfterEach
	void clearTenantContext() {
		TenantContextHolder.clear();
	}

	@Test
	void findByIdAndTenantIdForUpdateRejectsTenantIdThatDoesNotMatchContext() {
		TenantContextHolder.set(UUID.randomUUID());
		UUID suppliedTenantId = UUID.randomUUID();

		assertThatThrownBy(() -> repository.findByIdAndTenantIdForUpdate(UUID.randomUUID(), suppliedTenantId))
			.isInstanceOf(CrossTenantPersistenceException.class);

		verify(repository, never()).findByIdAndTenantIdForUpdateUnchecked(any(), any());
	}

	@Test
	void findByIdAndTenantIdForUpdateDelegatesWhenTenantIdMatchesContext() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		UUID id = UUID.randomUUID();
		ReactivationRequest expected = mock(ReactivationRequest.class);
		when(repository.findByIdAndTenantIdForUpdateUnchecked(id, tenantId)).thenReturn(Optional.of(expected));

		Optional<ReactivationRequest> result = repository.findByIdAndTenantIdForUpdate(id, tenantId);

		assertThat(result).contains(expected);
		verify(repository).findByIdAndTenantIdForUpdateUnchecked(id, tenantId);
	}

}
