package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FIN-01: narrow read of a teacher wallet (ADR-036) for the Finance "Student
 * Income" tab. The panel is shown only for the wallet owned by the signed-in
 * user's own {@code tenant_user} id - never inferred from an admin role and
 * never substituted with a finance account balance. Callers must already hold
 * the wallet view capability; this API performs no permission check itself.
 */
public interface TeacherWalletApi {

	Optional<WalletSummary> findWalletOwnedBy(UUID tenantUserId, int recentEntries);

	record WalletSummary(UUID teacherId, UUID walletId, String currency, BigDecimal balance,
			BigDecimal unpaidSettlements, BigDecimal walletEntries, List<WalletMovement> payIns,
			List<WalletMovement> payouts) {

	}

	/** One wallet-only entry (never a settlement event). */
	record WalletMovement(UUID entryId, String kind, BigDecimal amount, String description, Instant recordedAt) {

	}

}
