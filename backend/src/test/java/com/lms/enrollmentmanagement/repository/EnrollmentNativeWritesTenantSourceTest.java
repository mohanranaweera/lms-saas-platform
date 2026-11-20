package com.lms.enrollmentmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.common.tenant.TenantContextNotResolvedException;
import com.lms.enrollmentmanagement.domain.CourseParticipationSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * KLASS-T8b SEC L2: the two native, race-safe enrollment inserts no longer
 * expose a public repository method taking a tenant id. The SQL lives in
 * package-private fragment implementations that read the tenant from {@link
 * TenantContext} only, and fail loudly (no write) when it is unresolved.
 */
@Tag("cross-tenant")
class EnrollmentNativeWritesTenantSourceTest {

	private final EntityManager entityManager = mock(EntityManager.class);

	private final Query query = mock(Query.class);

	@BeforeEach
	void setUp() {
		when(entityManager.createNativeQuery(anyString())).thenReturn(query);
		when(query.setParameter(anyString(), any())).thenReturn(query);
		when(query.executeUpdate()).thenReturn(1);
	}

	@AfterEach
	void clearTenant() {
		TenantContextHolder.clear();
	}

	@Test
	void noRepositoryMethodAcceptsATenantIdOrIsAnUncheckedVariant() {
		for (Class<?> repository : new Class<?>[] { CourseParticipationRepository.class,
				LessonProgressRepository.class }) {
			for (Method method : repository.getMethods()) {
				assertThat(method.getName()).as(repository.getSimpleName()).doesNotContainIgnoringCase("unchecked");
				assertThat(Arrays.stream(method.getParameters()).map(Parameter::getName))
					.as(repository.getSimpleName() + "#" + method.getName())
					.doesNotContain("tenantId");
			}
		}
		assertThat(Modifier.isPublic(CourseParticipationWritesImpl.class.getModifiers())).isFalse();
		assertThat(Modifier.isPublic(LessonProgressWritesImpl.class.getModifiers())).isFalse();
	}

	@Test
	void participationInsertUsesTheContextTenant() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		CourseParticipationWritesImpl writes = new CourseParticipationWritesImpl(new TenantContextHolder());
		ReflectionTestUtils.setField(writes, "entityManager", entityManager);

		assertThat(writes.insertIfAbsent(UUID.randomUUID(), UUID.randomUUID(), CourseParticipationSource.SELF_ENROLL,
				Instant.now())).isEqualTo(1);

		verify(query).setParameter(eq("tenantId"), eq(tenantId));
	}

	@Test
	void progressInsertUsesTheContextTenant() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		LessonProgressWritesImpl writes = new LessonProgressWritesImpl(new TenantContextHolder());
		ReflectionTestUtils.setField(writes, "entityManager", entityManager);

		assertThat(writes.recordFirstView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()))
			.isEqualTo(1);

		verify(query).setParameter(eq("tenantId"), eq(tenantId));
	}

	@Test
	void anUnresolvedTenantContextWritesNothing() {
		CourseParticipationWritesImpl participation = new CourseParticipationWritesImpl(new TenantContextHolder());
		ReflectionTestUtils.setField(participation, "entityManager", entityManager);
		LessonProgressWritesImpl progress = new LessonProgressWritesImpl(new TenantContextHolder());
		ReflectionTestUtils.setField(progress, "entityManager", entityManager);

		assertThatThrownBy(() -> participation.insertIfAbsent(UUID.randomUUID(), UUID.randomUUID(),
				CourseParticipationSource.SELF_ENROLL, Instant.now()))
			.isInstanceOf(TenantContextNotResolvedException.class);
		assertThatThrownBy(() -> progress.recordFirstView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Instant.now()))
			.isInstanceOf(TenantContextNotResolvedException.class);
		verify(query, never()).executeUpdate();
	}

}
