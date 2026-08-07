package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.service.ParsedToken;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * A request against tenant A's subdomain carrying a manipulated
 * client-controlled header or JSON body field claiming tenant B's id must
 * still resolve to tenant A - tenant resolution reads the {@code Host}
 * header's subdomain exclusively, never a client-supplied identifier.
 */
class CrossTenantTenantIdHeaderManipulationIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void aManipulatedXTenantIdHeaderAndBodyFieldClaimingTenantBAreBothIgnoredWhenHostResolvesTenantA() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-b"));
		seedActiveStudent(tenantA.getId(), "student@example.test");

		HttpResult<LoginResponse> response = loginWithManipulatedTenantId(
				hostFor(tenantA.getSubdomain()), "student@example.test", RAW_PASSWORD, tenantB.getId(),
				tenantB.getId().toString());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String accessToken = response.getBody().data().accessToken();
		var sessionId = response.getBody().data().sessionId();

		ParsedToken parsedToken = tokenService.parseAndValidate(accessToken);
		assertThat(parsedToken.tenantId()).isEqualTo(tenantA.getId());
		assertThat(parsedToken.tenantId()).isNotEqualTo(tenantB.getId());

		// The persisted session row was also written under tenant A, never tenant B.
		DeviceSession session = withTenant(tenantA.getId(), () -> deviceSessionRepository.findById(sessionId))
			.orElseThrow();
		assertThat(session.getTenantId()).isEqualTo(tenantA.getId());

		// Confirm tenant B genuinely received nothing out of this request.
		List<DeviceSession> tenantBSessions = withTenant(tenantB.getId(), () -> deviceSessionRepository.findAll());
		assertThat(tenantBSessions).isEmpty();
	}

}
