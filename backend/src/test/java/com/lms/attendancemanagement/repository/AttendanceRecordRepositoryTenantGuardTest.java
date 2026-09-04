package com.lms.attendancemanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.tenant.TenantContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Defense-in-depth coverage (post-ship review) for the tenant-id guard added
 * to {@link AttendanceRecordRepository#upsertRecord} and {@link
 * AttendanceRecordRepository#findDistinctCourseIdsByTenantId} - the only two
 * methods on this repository that take {@code tenantId} as an explicit
 * parameter instead of relying on {@code TenantAwareRepositoryImpl}'s
 * structural {@code Specification} filtering (see the interface's
 * class-level javadoc).
 *
 * <p>No dedicated repository-level test class exists anywhere else in this
 * codebase (repository behavior is otherwise exercised indirectly through
 * service-level Mockito tests and HTTP-level integration tests), so this
 * class is deliberately narrow: it proves only the new guard's own logic,
 * exercising the interface's real {@code default} methods via {@link
 * org.mockito.Mockito#CALLS_REAL_METHODS} while stubbing the underlying
 * {@code @Query}-backed {@code *Unchecked} methods - no Spring context or
 * database is needed for this.
 */
class AttendanceRecordRepositoryTenantGuardTest {

	private final AttendanceRecordRepository repository = mock(AttendanceRecordRepository.class,
			CALLS_REAL_METHODS);

	@AfterEach
	void clearTenantContext() {
		TenantContextHolder.clear();
	}

	@Test
	void upsertRecordRejectsTenantIdThatDoesNotMatchContext() {
		TenantContextHolder.set(UUID.randomUUID());
		UUID suppliedTenantId = UUID.randomUUID();

		assertThatThrownBy(() -> repository.upsertRecord(UUID.randomUUID(), suppliedTenantId, UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), "PRESENT", UUID.randomUUID(), Instant.now(), Instant.now()))
			.isInstanceOf(CrossTenantPersistenceException.class);

		verify(repository, never()).upsertRecordUnchecked(any(), any(), any(), any(), any(), any(), any(), any(),
				any());
	}

	@Test
	void upsertRecordDelegatesWhenTenantIdMatchesContext() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		UUID id = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		UUID markedBy = UUID.randomUUID();
		Instant now = Instant.now();

		repository.upsertRecord(id, tenantId, courseId, sessionId, studentId, "PRESENT", markedBy, now, now);

		verify(repository).upsertRecordUnchecked(eq(id), eq(tenantId), eq(courseId), eq(sessionId), eq(studentId),
				eq("PRESENT"), eq(markedBy), eq(now), eq(now));
	}

	@Test
	void findDistinctCourseIdsByTenantIdRejectsTenantIdThatDoesNotMatchContext() {
		TenantContextHolder.set(UUID.randomUUID());
		UUID suppliedTenantId = UUID.randomUUID();

		assertThatThrownBy(() -> repository.findDistinctCourseIdsByTenantId(suppliedTenantId))
			.isInstanceOf(CrossTenantPersistenceException.class);

		verify(repository, never()).findDistinctCourseIdsByTenantIdUnchecked(any());
	}

	@Test
	void findDistinctCourseIdsByTenantIdDelegatesWhenTenantIdMatchesContext() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		List<UUID> expected = List.of(UUID.randomUUID());
		when(repository.findDistinctCourseIdsByTenantIdUnchecked(tenantId)).thenReturn(expected);

		List<UUID> result = repository.findDistinctCourseIdsByTenantId(tenantId);

		assertThat(result).isEqualTo(expected);
	}

}
