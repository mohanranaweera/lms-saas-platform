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
import com.lms.common.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

	private AuditLogService service;

	@BeforeEach
	void setUp() {
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		service = new AuditLogService(auditLogRepository, tenantContext, new ObjectMapper());
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

	/** A bean whose getter always throws, so Jackson fails to serialize it. */
	public static class Unserializable {

		public String getValue() {
			throw new RuntimeException("boom - deliberately unserializable for this test");
		}

	}

}
