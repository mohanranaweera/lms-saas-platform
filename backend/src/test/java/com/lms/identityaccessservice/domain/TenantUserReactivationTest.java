package com.lms.identityaccessservice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** STAFF-MGMT-02: suspend/activate keeps an unfinished invitation an invitation. */
class TenantUserReactivationTest {

	private static final UUID TENANT = UUID.randomUUID();

	@Test
	void anInviteeSuspendedBeforeSetupIsReactivatedAsInvited() {
		TenantUser user = TenantUser.invited(TENANT, "invitee@example.test", "unusable", Role.OPERATOR);
		user.suspend();
		user.suspend(); // idempotent: still remembered as an invitee
		assertThat(user.getStatus()).isEqualTo(AccountStatus.SUSPENDED);

		user.activate();
		assertThat(user.getStatus()).isEqualTo(AccountStatus.INVITED);

		// Once the invitee sets a password the next deactivate/reactivate cycle is a normal one.
		user.setOwnPassword("hash", Instant.now());
		assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
		user.suspend();
		user.activate();
		assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

	@Test
	void anActiveAccountIsReactivatedAsActive() {
		TenantUser user = new TenantUser(TENANT, "staff@example.test", "hash", Role.OPERATOR);
		user.suspend();
		user.activate();
		assertThat(user.getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

}
