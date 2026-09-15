package com.lms.auditlogmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * Plain Mockito unit test directly against {@link AuditLogService},
 * mirroring {@code AuditLogQueryServiceTest}/{@code
 * AuditLogEventListenerTest}'s established style for this module: no Spring
 * context, mock the repository/tenant-context collaborators. Uses a real
 * {@link ObjectMapper} (rather than a mocked one, since {@code
 * AuditLogService}'s {@code ObjectMapper} is a normal constructor-injected
 * collaborator with no seams to stub {@code writeValueAsString} without
 * mocking a third-party class) so the {@code serializeMetadata} ->
 * {@code JacksonException} -> {@code IllegalStateException} branch is
 * exercised with a metadata value that genuinely fails to serialize - a
 * custom bean whose getter throws, which Jackson wraps into a real {@code
 * DatabindException} (a {@code JacksonException} subtype) by default.
 *
 * <p>{@link #userProvisioningApi} is stubbed lenient-by-default (via
 * {@code MockitoSettings(strictness = Strictness.LENIENT)}) to return {@code
 * true} for {@link UserProvisioningApi#actorExists}, so every pre-existing
 * happy-path test below keeps passing under the C1 actor-existence guard
 * without individually re-stubbing it; tests that specifically exercise the
 * guard's rejection path override that default explicitly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ACTOR_ID = UUID.randomUUID();

	@Mock
	private AuditLogRepository auditLogRepository;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private EntityManager entityManager;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	private AuditLogService service;

	@BeforeEach
	void setUp() {
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(userProvisioningApi.actorExists(any())).thenReturn(true);
		service = new AuditLogService(auditLogRepository, tenantContext, new ObjectMapper(), entityManager,
				userProvisioningApi);
	}

	@AfterEach
	void clearTenantContextHolder() {
		// recordForTenant's guard reads the static TenantContextHolder, not
		// the mocked TenantContext bean - clear it so no test here leaks
		// state into another test on a shared JVM thread.
		TenantContextHolder.clear();
	}

	@Test
	void recordNullEntryThrowsIllegalArgumentExceptionAndNeverTouchesTheRepository() {
		assertThatThrownBy(() -> service.record(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entry");

		verifyNoInteractions(auditLogRepository);
	}

	@Test
	void recordWithNoMetadataSavesAnAuditLogWithNullMetadataAndTheTenantFromTheTrustedContext() {
		UUID targetId = UUID.randomUUID();
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "course.price_changed", "course", targetId);

		service.record(entry);

		ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
		verify(auditLogRepository).save(captor.capture());
		AuditLog saved = captor.getValue();
		assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
		assertThat(saved.getActorId()).isEqualTo(ACTOR_ID);
		assertThat(saved.getAction()).isEqualTo("course.price_changed");
		assertThat(saved.getTargetEntity()).isEqualTo("course");
		assertThat(saved.getTargetId()).isEqualTo(targetId);
		assertThat(saved.getMetadata()).isNull();
	}

	@Test
	void recordWithMetadataSavesAnAuditLogWithMetadataSerializedToJson() {
		UUID targetId = UUID.randomUUID();
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", targetId, null,
				Map.of("previousPrice", "10.00"));

		service.record(entry);

		ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
		verify(auditLogRepository).save(captor.capture());
		assertThat(captor.getValue().getMetadata()).contains("previousPrice").contains("10.00");
	}

	/**
	 * Exercises {@code AuditLogService#serializeMetadata}'s {@code
	 * JacksonException} -> {@code IllegalStateException} branch: {@link
	 * Unserializable#getValue()} throws, which Jackson's default bean
	 * serializer wraps into a {@code DatabindException} (a {@code
	 * JacksonException}) while serializing the entry's metadata map - proving
	 * a metadata map that cannot be serialized fails loudly rather than
	 * silently persisting an incomplete audit row.
	 */
	@Test
	void recordWithMetadataThatFailsToSerializeThrowsIllegalStateExceptionAndNeverSaves() {
		UUID targetId = UUID.randomUUID();
		AuditLogEntry entry = new AuditLogEntry(ACTOR_ID, "course.price_changed", "course", targetId, null,
				Map.of("bad", new Unserializable()));

		assertThatThrownBy(() -> service.record(entry)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Failed to serialize audit log metadata");

		verify(auditLogRepository, org.mockito.Mockito.never()).save(any());
	}

	/**
	 * C1: {@code record()}'s actor-existence guard rejects an actor id
	 * {@link UserProvisioningApi#actorExists} does not recognize, before ever
	 * calling {@code auditLogRepository.save(...)}, with the distinguishable
	 * {@link UnknownAuditActorException} (Low finding fix: previously a
	 * generic {@link IllegalArgumentException} that fell into {@code
	 * GlobalExceptionHandler}'s undifferentiated {@code 500} catch-all).
	 */
	@Test
	void recordWithAnUnresolvableActorIdThrowsUnknownAuditActorExceptionAndNeverSaves() {
		when(userProvisioningApi.actorExists(ACTOR_ID)).thenReturn(false);
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "course.price_changed", "course", UUID.randomUUID());

		assertThatThrownBy(() -> service.record(entry)).isInstanceOf(UnknownAuditActorException.class)
			.satisfies(ex -> {
				UnknownAuditActorException actual = (UnknownAuditActorException) ex;
				assertThat(actual.getErrorCode()).isEqualTo(ApiErrorCodes.UNKNOWN_AUDIT_ACTOR);
				assertThat(actual.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
				// The client-facing message must never leak the raw actor id -
				// see UnknownAuditActorException's javadoc.
				assertThat(actual.getMessage()).doesNotContain(ACTOR_ID.toString());
			});

		verifyNoInteractions(auditLogRepository);
	}

	// ------------------------------------------------------------------
	// recordForTenant (C2).
	// ------------------------------------------------------------------

	@Test
	void recordForTenantNullTenantIdThrowsIllegalArgumentExceptionAndNeverTouchesTheEntityManager() {
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "tenant.status_changed", "tenant", UUID.randomUUID());

		assertThatThrownBy(() -> service.recordForTenant(null, entry)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("tenantId");

		verifyNoInteractions(entityManager);
	}

	@Test
	void recordForTenantNullEntryThrowsIllegalArgumentExceptionAndNeverTouchesTheEntityManager() {
		assertThatThrownBy(() -> service.recordForTenant(UUID.randomUUID(), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entry");

		verifyNoInteractions(entityManager);
	}

	/**
	 * Happy path: no {@link TenantContextHolder} value set (mirrors the real
	 * Platform Admin request state), a known actor - persists via {@link
	 * EntityManager#persist}, NEVER {@code auditLogRepository.save(...)}, and
	 * the persisted {@link AuditLog}'s {@code tenantId}/{@code actorId} come
	 * exactly from this method's own parameters, never from any ambient
	 * tenant context (there is none to read from here).
	 */
	@Test
	void recordForTenantHappyPathPersistsViaEntityManagerNotRepositorySaveWithExactTenantAndActorIds() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		UUID targetTenantId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "tenant.status_changed", "tenant", targetId);

		service.recordForTenant(targetTenantId, entry);

		ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
		verify(entityManager).persist(captor.capture());
		verifyNoInteractions(auditLogRepository);
		AuditLog persisted = captor.getValue();
		assertThat(persisted.getTenantId()).isEqualTo(targetTenantId);
		assertThat(persisted.getActorId()).isEqualTo(ACTOR_ID);
		assertThat(persisted.getAction()).isEqualTo("tenant.status_changed");
		assertThat(persisted.getTargetEntity()).isEqualTo("tenant");
		assertThat(persisted.getTargetId()).isEqualTo(targetId);
	}

	/**
	 * H3: calling {@code recordForTenant} while a {@link TenantContextHolder}
	 * value IS resolved (i.e. from what should have been a {@code record()}
	 * call site) is rejected with {@link IllegalStateException}, never
	 * silently persisted.
	 */
	@Test
	void recordForTenantThrowsIllegalStateExceptionWhenATenantContextIsResolved() {
		TenantContextHolder.set(UUID.randomUUID());
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "tenant.status_changed", "tenant", UUID.randomUUID());

		assertThatThrownBy(() -> service.recordForTenant(UUID.randomUUID(), entry))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TenantContext");

		verifyNoInteractions(entityManager);
	}

	/**
	 * C1: {@code recordForTenant}'s actor-existence guard rejects an actor id
	 * {@link UserProvisioningApi#actorExists} does not recognize, before ever
	 * calling {@link EntityManager#persist}, with the same distinguishable
	 * {@link UnknownAuditActorException} as {@link #record}'s guard.
	 */
	@Test
	void recordForTenantWithAnUnresolvableActorIdThrowsUnknownAuditActorExceptionAndNeverPersists() {
		when(userProvisioningApi.actorExists(ACTOR_ID)).thenReturn(false);
		AuditLogEntry entry = AuditLogEntry.of(ACTOR_ID, "tenant.status_changed", "tenant", UUID.randomUUID());

		assertThatThrownBy(() -> service.recordForTenant(UUID.randomUUID(), entry))
			.isInstanceOf(UnknownAuditActorException.class);

		verifyNoInteractions(entityManager);
	}

	/** A bean whose getter always throws, so Jackson fails to serialize it. */
	public static class Unserializable {

		public String getValue() {
			throw new RuntimeException("boom - deliberately unserializable for this test");
		}

	}

}
