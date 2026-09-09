package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.repository.NotificationTemplateRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic, non-async, plain Mockito unit test directly against {@link
 * NotificationDispatchService#dispatchOne(UUID)} - proves the {@code
 * try/finally} structure around {@link TenantContextHolder}, and the
 * claim/finalize orchestration shape, directly - with no thread-pool/timing
 * dependency and no Spring context/Testcontainers.
 *
 * <p>{@link NotificationDispatchClaimService} and {@link
 * NotificationDispatchFinalizeService} are mocked here rather than
 * constructed for real - their own transactional/persistence behavior is out
 * of scope for this test (covered by the Testcontainers-backed {@code
 * NotificationDispatchIntegrationTest} instead); this test's only job is
 * {@code dispatchOne}'s own orchestration and tenant-context symmetry.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatchServiceTenantContextSymmetryTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID OUTBOX_ID = UUID.randomUUID();

	private static final UUID RECIPIENT_USER_ID = UUID.randomUUID();

	@Mock
	private NotificationTemplateRepository notificationTemplateRepository;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private MessagingProviderApi messagingProviderApi;

	@Mock
	private NotificationTemplateRenderer notificationTemplateRenderer;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private NotificationDispatchClaimService notificationDispatchClaimService;

	@Mock
	private NotificationDispatchFinalizeService notificationDispatchFinalizeService;

	private NotificationDispatchService service;

	private NotificationTemplate template;

	@BeforeEach
	void setUp() {
		service = new NotificationDispatchService(notificationTemplateRepository, userProvisioningApi,
				messagingProviderApi, notificationTemplateRenderer, objectMapper, notificationDispatchClaimService,
				notificationDispatchFinalizeService);

		NotificationOutbox row = new NotificationOutbox(TENANT_ID, NotificationEventType.PAYMENT_CONFIRMED,
				RECIPIENT_USER_ID, "{\"amount\":\"10.00\"}", java.time.Instant.now());
		// id is normally assigned by Hibernate's UUIDv7 generator at persist
		// time - this plain construction never persists, so it must be set
		// explicitly for dispatchOne's row.getId() calls (passed through to
		// the mocked finalize service) to match OUTBOX_ID, mirroring this
		// codebase's established ReflectionTestUtils pattern for the same
		// situation (see e.g. SlipReviewServiceTest).
		ReflectionTestUtils.setField(row, "id", OUTBOX_ID);
		when(notificationDispatchClaimService.claim(OUTBOX_ID)).thenReturn(Optional.of(row));
		when(userProvisioningApi.findTenantUserSummaries(Set.of(RECIPIENT_USER_ID)))
			.thenReturn(java.util.List.of(new TenantUserSummary(RECIPIENT_USER_ID, "student@example.test", "STUDENT",
					"active")));
		template = new NotificationTemplate(TENANT_ID, "PAYMENT_CONFIRMED", "Subject", "Body");
		when(notificationTemplateRepository.findByTemplateKey("PAYMENT_CONFIRMED")).thenReturn(Optional.of(template));
		when(objectMapper.readValue(anyString(), org.mockito.ArgumentMatchers.<TypeReference<Map<String, Object>>>any()))
			.thenReturn(Map.of("amount", "10.00"));
		when(notificationTemplateRenderer.render(eq(template), any())).thenReturn(
				new RenderedNotification("Rendered subject", "Rendered body"));
	}

	@AfterEach
	void clearTenantContextRegardlessOfTestOutcome() {
		// Defensive - must not leak thread-local tenant state to other tests
		// sharing this JVM's pooled test-execution thread, independent of
		// whether the assertions above (which already expect it cleared)
		// actually ran/passed.
		TenantContextHolder.clear();
	}

	@Test
	void tenantContextIsClearedAfterANormalSuccessfulDispatchReturn() {
		service.dispatchOne(OUTBOX_ID);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void aSuccessfulDispatchClaimsFirstThenSendsThenFinalizesAsSent() {
		service.dispatchOne(OUTBOX_ID);

		verify(notificationDispatchClaimService).claim(OUTBOX_ID);
		verify(messagingProviderApi).sendEmail("student@example.test", "Rendered subject", "Rendered body");
		verify(notificationDispatchFinalizeService).markSent(OUTBOX_ID, TENANT_ID, RECIPIENT_USER_ID,
				"Rendered subject", "Rendered body");
		verify(notificationDispatchFinalizeService, never()).markFailed(any(), any());
	}

	@Test
	void tenantContextIsClearedAfterACollaboratorThrowsMidDispatch() {
		doThrow(new RuntimeException("Simulated SMTP failure")).when(messagingProviderApi)
			.sendEmail(anyString(), anyString(), anyString());

		// dispatchOne's own internal try/catch swallows this - the exception
		// must never propagate out of the method at all.
		service.dispatchOne(OUTBOX_ID);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void aFailedSendFinalizesAsFailedNeverAsSent() {
		doThrow(new RuntimeException("Simulated SMTP failure")).when(messagingProviderApi)
			.sendEmail(anyString(), anyString(), anyString());

		service.dispatchOne(OUTBOX_ID);

		verify(notificationDispatchFinalizeService).markFailed(OUTBOX_ID, TENANT_ID);
		verify(notificationDispatchFinalizeService, never()).markSent(any(), any(), any(), anyString(), anyString());
	}

	/**
	 * Direct proof (post-ship review finding), not just an inference from
	 * the set/clear symmetry tests above: {@link
	 * NotificationTemplateRepository#findByTemplateKey}, the dispatch path's
	 * own tenant-scoped repository read, is actually invoked WHILE {@link
	 * TenantContextHolder} is set to the claimed row's own {@code tenantId} -
	 * not merely set at some point before/after. A Mockito {@link
	 * org.mockito.stubbing.Answer} reads the thread-local at the exact moment
	 * the stubbed method is called, which a before/after assertion around
	 * {@code dispatchOne} could never distinguish from "set to the wrong
	 * value" or "not actually set during the call at all".
	 */
	@Test
	void theTemplateRepositoryReadHappensWhileTenantContextHolderIsSetToTheClaimedRowsOwnTenantId() {
		AtomicReference<UUID> tenantIdAtCallTime = new AtomicReference<>();
		when(notificationTemplateRepository.findByTemplateKey("PAYMENT_CONFIRMED")).thenAnswer(invocation -> {
			tenantIdAtCallTime.set(TenantContextHolder.isSet() ? new TenantContextHolder().getTenantId() : null);
			return Optional.of(template);
		});

		service.dispatchOne(OUTBOX_ID);

		assertThat(tenantIdAtCallTime.get()).isEqualTo(TENANT_ID);
	}

	@Test
	void anUnsuccessfulClaimIsACompleteNoOpWithNoDownstreamCollaboratorCalls() {
		when(notificationDispatchClaimService.claim(OUTBOX_ID)).thenReturn(Optional.empty());

		service.dispatchOne(OUTBOX_ID);

		verifyNoInteractions(userProvisioningApi, messagingProviderApi, notificationDispatchFinalizeService);
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

}
