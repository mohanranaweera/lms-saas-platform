package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import db.migration.V108__backfill_tenant_user_login_mobile.Candidate;
import db.migration.V108__backfill_tenant_user_login_mobile.Plan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-031 backfill decisions (the pure step of V108): only valid numbers that
 * are unique within their tenant and not already someone's sign-in mobile are
 * assigned; uniqueness is per tenant, and each tenant's region applies.
 */
class V108BackfillLoginMobileTest {

	private static final UUID TENANT_A = UUID.randomUUID();

	private static final UUID TENANT_B = UUID.randomUUID();

	@Test
	void aValidUniqueNumberIsAssignedInE164() {
		UUID user = UUID.randomUUID();

		Plan plan = V108__backfill_tenant_user_login_mobile.plan(List.of(new Candidate(TENANT_A, user, "077 123 4567")),
				Map.of(), Set.of());

		assertThat(plan.assignments()).containsExactlyEntriesOf(Map.of(user, "+94771234567"));
	}

	@Test
	void studentsSharingANumberInOneTenantGetNoneOfIt() {
		UUID sibling1 = UUID.randomUUID();
		UUID sibling2 = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		Plan plan = V108__backfill_tenant_user_login_mobile.plan(List.of(new Candidate(TENANT_A, sibling1, "0771234567"),
				new Candidate(TENANT_A, sibling2, "+94 77 123 4567"), new Candidate(TENANT_A, other, "0719876543")),
				Map.of(), Set.of());

		assertThat(plan.assignments()).containsOnlyKeys(other);
		assertThat(plan.duplicate()).isEqualTo(2);
	}

	@Test
	void theSameNumberInTwoTenantsIsAssignedInBoth() {
		UUID inA = UUID.randomUUID();
		UUID inB = UUID.randomUUID();

		Plan plan = V108__backfill_tenant_user_login_mobile.plan(
				List.of(new Candidate(TENANT_A, inA, "0771234567"), new Candidate(TENANT_B, inB, "0771234567")),
				Map.of(), Set.of());

		assertThat(plan.assignments()).containsOnlyKeys(inA, inB);
	}

	@Test
	void aNumberAlreadyUsedForSignInIsNotAssignedAgain() {
		UUID user = UUID.randomUUID();

		Plan plan = V108__backfill_tenant_user_login_mobile.plan(List.of(new Candidate(TENANT_A, user, "0771234567")),
				Map.of(), Set.of(TENANT_A + "|+94771234567"));

		assertThat(plan.assignments()).isEmpty();
		assertThat(plan.alreadyTaken()).isEqualTo(1);
	}

	@Test
	void invalidNumbersAreCountedAndSkipped() {
		Plan plan = V108__backfill_tenant_user_login_mobile.plan(
				List.of(new Candidate(TENANT_A, UUID.randomUUID(), "n/a"),
						new Candidate(TENANT_A, UUID.randomUUID(), "0112345678")),
				Map.of(), Set.of());

		assertThat(plan.assignments()).isEmpty();
		assertThat(plan.invalid()).isEqualTo(2);
	}

	@Test
	void eachTenantsRegionIsUsedForLocalNumbers() {
		UUID uk = UUID.randomUUID();

		Plan plan = V108__backfill_tenant_user_login_mobile.plan(List.of(new Candidate(TENANT_B, uk, "07400 123456")),
				Map.of(TENANT_B, "GB"), Set.of());

		assertThat(plan.assignments()).containsExactlyEntriesOf(Map.of(uk, "+447400123456"));
	}

}
