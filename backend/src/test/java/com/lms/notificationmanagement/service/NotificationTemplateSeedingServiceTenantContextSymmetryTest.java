package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.tenantmanagement.api.TenantRegisteredEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Deterministic, non-async, plain Mockito unit test directly against {@link
 * NotificationTemplateSeedingService#onTenantRegistered(TenantRegisteredEvent)}
 * - proves its independent {@code TenantContextHolder} try/finally is
 * symmetric on a normal return, when a collaborator throws an unexpected
 * exception mid-method, and when a collaborator throws the specific,
 * internally-swallowed lost-seed-race exception (Fix 2). Same
 * technique/rigor as {@code
 * NotificationDispatchServiceTenantContextSymmetryTest} (see that class's
 * javadoc) - this module's last of four remaining independently-managed
 * {@code TenantContextHolder} call sites.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationTemplateSeedingServiceTenantContextSymmetryTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private NotificationTemplateSeedWriter notificationTemplateSeedWriter;

	private NotificationTemplateSeedingService service;

	@AfterEach
	void clearTenantContextRegardlessOfTestOutcome() {
		TenantContextHolder.clear();
	}

	@Test
	void onTenantRegisteredClearsTenantContextAfterANormalSuccessfulReturn() {
		service = new NotificationTemplateSeedingService(notificationTemplateSeedWriter);
		doNothing().when(notificationTemplateSeedWriter).seedIfAbsent(any(), anyString(), anyString(), anyString());

		service.onTenantRegistered(event());

		verify(notificationTemplateSeedWriter, times(3)).seedIfAbsent(any(), anyString(), anyString(), anyString());
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onTenantRegisteredClearsTenantContextEvenWhenAnUnexpectedExceptionEscapes() {
		service = new NotificationTemplateSeedingService(notificationTemplateSeedWriter);
		doThrow(new RuntimeException("Simulated unexpected failure")).when(notificationTemplateSeedWriter)
			.seedIfAbsent(any(), anyString(), anyString(), anyString());

		assertThatThrownBy(() -> service.onTenantRegistered(event())).isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onTenantRegisteredClearsTenantContextWhenALostSeedRaceIsInternallySwallowed() {
		service = new NotificationTemplateSeedingService(notificationTemplateSeedWriter);
		// A lost race against uq_notification_template_tenant_key on the FIRST
		// seed attempt only - Fix 2's expected, safely-ignorable case - is
		// caught internally by the service itself, so all three seedIfAbsent
		// calls still happen and onTenantRegistered still returns normally.
		doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint "
				+ "\"uq_notification_template_tenant_key\""))
			.doNothing()
			.doNothing()
			.when(notificationTemplateSeedWriter)
			.seedIfAbsent(any(), anyString(), anyString(), anyString());

		service.onTenantRegistered(event());

		verify(notificationTemplateSeedWriter, times(3)).seedIfAbsent(any(), anyString(), anyString(), anyString());
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	private static TenantRegisteredEvent event() {
		return new TenantRegisteredEvent(TENANT_ID, "Test Institute", Instant.now());
	}

}
