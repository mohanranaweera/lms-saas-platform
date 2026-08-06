package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Proves AUTH-2's "re-verify the actor's current role/status server-side,
 * never trust the token payload as final word" requirement for the
 * suspended-mid-session case specifically - distinct from
 * {@link LogoutIntegrationTest}, which proves the same principle for a
 * revoked *session* row. Here the session row is untouched (still
 * {@code ACTIVE}, not expired) and the JWT itself is still signature-valid
 * and unexpired; only the user's live {@code status} has changed since the
 * token was issued.
 */
class SuspendedUserMidSessionIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void aStillValidAccessTokenIsRejectedAfterTheUserIsSuspendedMidSession() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		TenantUser user = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());

		HttpResult<LoginResponse> loginResponse = login(host, "student@example.test", RAW_PASSWORD);
		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		String accessToken = loginResponse.getBody().data().accessToken();

		// Suspend the account directly - the device_session row itself is left
		// untouched (still ACTIVE, not expired/revoked): only the user's live
		// status has changed since the token was issued.
		suspendTenantUser(user.getId());

		// The JWT is still signature-valid and unexpired; only a live re-read of
		// tenant_user.status (not the JWT's own role claim) can catch this.
		HttpResult<Void> replay = logout(host, accessToken);

		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(replay.getBody().success()).isFalse();
		assertThat(replay.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

}
