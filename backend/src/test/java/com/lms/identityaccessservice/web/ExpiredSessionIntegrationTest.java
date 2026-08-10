package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The other half of {@code DeviceSession#isUsable(Instant)}: {@link
 * LogoutIntegrationTest} already covers a {@code REVOKED} session blocking an
 * otherwise-valid access token. This proves the {@code expires_at}-in-the
 * -past half blocks it too, even though the access-token JWT's own {@code
 * exp} claim (a completely independent expiry, minted at login with the
 * configured access-token TTL) is still unexpired and the session row's
 * {@code status} is still {@code active} - i.e. {@code
 * JwtAuthenticationFilter#isTenantSessionUsable} really re-checks the session
 * row's own expiry, not just its {@code status} column.
 */
class ExpiredSessionIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void anActiveButExpiredSessionRowBlocksAnOtherwiseValidUnexpiredAccessToken() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());

		HttpResult<LoginResponse> loginResponse = login(host, "student@example.test", RAW_PASSWORD);
		String accessToken = loginResponse.getBody().data().accessToken();
		UUID sessionId = loginResponse.getBody().data().sessionId();

		// Backdate only the session row's own expires_at - status stays 'active', and the
		// access-token JWT itself (already issued, with its own independent exp claim) is
		// untouched and still unexpired.
		jdbcTemplate.update("UPDATE device_session SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), sessionId);

		HttpResult<Void> attempt = logout(host, accessToken);

		assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(attempt.getBody().success()).isFalse();
		assertThat(attempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

}
