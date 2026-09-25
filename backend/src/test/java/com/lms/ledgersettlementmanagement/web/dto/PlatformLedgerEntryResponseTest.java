package com.lms.ledgersettlementmanagement.web.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * L2: {@link PlatformLedgerEntryResponse}'s compact constructor rejects a
 * {@code null} {@code tenantId} - this record represents a cross-tenant row
 * that must always carry a real tenant discriminator (plan §14/§15),
 * mirroring the house style already established by {@code AuditLogEntry}'s
 * own compact constructor.
 */
class PlatformLedgerEntryResponseTest {

	@Test
	void constructionWithANullTenantIdThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new PlatformLedgerEntryResponse(UUID.randomUUID(), null, "Acme Institute",
				UUID.randomUUID(), UUID.randomUUID(), LedgerEntryType.PAYMENT_CONFIRMED, new BigDecimal("10.00"), null,
				Instant.now(), null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("tenantId");
	}

}
