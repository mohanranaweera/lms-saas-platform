package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.error.RequestRefusedException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.domain.ImpersonationSession;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.ImpersonationSessionRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;

/**
 * STAFF-MGMT-03 review: re-validating a Log as Student session without a resolved tenant (or for
 * another tenant's session) fails closed - false / 401, never a 500 and never a write.
 */
class ImpersonationServiceTenantContextTest {

	private final ImpersonationSessionRepository sessions = mock(ImpersonationSessionRepository.class);

	private final TenantUserRepository users = mock(TenantUserRepository.class);

	private final TenantContext tenantContext = mock(TenantContext.class);

	private final AuditLogApi audit = mock(AuditLogApi.class);

	private final PermissionCheckService permissions = mock(PermissionCheckService.class);

	private final ImpersonationService service = new ImpersonationService(sessions, users,
			mock(DeviceSessionRepository.class), mock(DeviceSessionCacheService.class), mock(TokenService.class),
			permissions, audit, tenantContext, mock(Environment.class), Duration.ofMinutes(30));

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
	}

	@Test
	void withoutATenantContextTheSessionIsUnusableAndTheExchangeIs401() {
		assertThat(service.isTokenSessionUsable(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).isFalse();
		assertThatThrownBy(() -> service.exchange("handoff-secret")).isInstanceOf(RequestRefusedException.class)
			.satisfies(ex -> assertThat(((RequestRefusedException) ex).getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
		verifyNoInteractions(sessions, users, audit, permissions);
	}

	@Test
	void aSessionOfAnotherTenantIsUnusableWithoutAnyWrite() {
		UUID current = UUID.randomUUID();
		TenantContextHolder.set(current);
		when(tenantContext.getTenantId()).thenReturn(current);
		UUID actor = UUID.randomUUID();
		UUID subject = UUID.randomUUID();
		ImpersonationSession other = new ImpersonationSession(UUID.randomUUID(), actor, UUID.randomUUID(), subject,
				UUID.randomUUID(), "1", "Sub Ject", "Support ticket", "hash", Instant.now(),
				Instant.now().plus(Duration.ofMinutes(10)));
		UUID sessionId = UUID.randomUUID();
		when(sessions.findById(sessionId)).thenReturn(Optional.of(other));

		assertThat(service.isTokenSessionUsable(sessionId, subject, actor)).isFalse();
		verify(sessions, never()).save(any());
		verifyNoInteractions(users, audit, permissions);
	}

}
