package com.lms.paymentmanagement.slip.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Structural coverage (plan §10/§18): {@link SlipApproveRequest} and {@link
 * SlipRejectRequest} - the two client-facing mutation request bodies for
 * MVP-011's review endpoints - must never expose a client-settable {@code
 * tenantId}/{@code studentId}/{@code status}/{@code reviewerId} field. Tenant
 * identity, the acting reviewer, and the slip's status are always resolved
 * server-side ({@code TenantContext}/{@code AuthenticatedPrincipalHolder}/the
 * slip's own state machine) - never accepted from the request body, per root
 * {@code CLAUDE.md}'s "never trust a tenant_id supplied by a normal frontend
 * user" rule.
 *
 * <p>Reflects directly over each record's components rather than relying on
 * JSON (de)serialization behavior, so this test fails loudly even if a future
 * change accidentally adds one of these fields without also remembering to
 * {@code @JsonIgnore} it.
 */
class SlipRequestDtoShapeTest {

	private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of("tenantId", "studentId", "status", "reviewerId");

	@Test
	void slipApproveRequestHasNoClientSettableTenantOrIdentityOrStatusField() {
		assertRecordHasNoForbiddenComponent(SlipApproveRequest.class);
	}

	@Test
	void slipRejectRequestHasNoClientSettableTenantOrIdentityOrStatusField() {
		assertRecordHasNoForbiddenComponent(SlipRejectRequest.class);
	}

	private static void assertRecordHasNoForbiddenComponent(Class<?> recordType) {
		assertThat(recordType.isRecord()).as("%s must be a record", recordType.getSimpleName()).isTrue();
		Set<String> componentNames = Arrays.stream(recordType.getRecordComponents())
			.map(RecordComponent::getName)
			.collect(Collectors.toSet());

		assertThat(componentNames).as("%s's record components", recordType.getSimpleName())
			.doesNotContainAnyElementsOf(FORBIDDEN_FIELD_NAMES);
	}

}
