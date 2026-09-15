package com.lms.auditlogmanagement.web.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * L2: {@link PlatformAuditLogEntryResponse}'s compact constructor rejects a
 * {@code null} {@code tenantId} - this record represents a cross-tenant row
 * that must always carry a real tenant discriminator (plan §14/§15),
 * mirroring the house style already established by {@code AuditLogEntry}'s
 * own compact constructor.
 */
class PlatformAuditLogEntryResponseTest {

	@Test
	void constructionWithANullTenantIdThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new PlatformAuditLogEntryResponse(UUID.randomUUID(), null, "Acme Institute",
				UUID.randomUUID(), "tenant.status_changed", "tenant", UUID.randomUUID(), null, null, Instant.now()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("tenantId");
	}

}
