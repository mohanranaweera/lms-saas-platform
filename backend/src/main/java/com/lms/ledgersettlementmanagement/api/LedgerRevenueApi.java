package com.lms.ledgersettlementmanagement.api;

import java.time.Instant;
import java.util.List;

/**
 * Wave 7 (§4) - the read contract {@code finance-expense-management} uses for
 * income reporting. Income is derived ONLY from {@code ledger_entry} through
 * this interface - that domain never stores its own income rows and never
 * reaches into {@code LedgerEntryRepository}/{@code LedgerEntry} directly
 * (spec 23 §8). Tenant identity comes from the already-resolved {@link
 * com.lms.common.tenant.TenantContext}; there is no tenant-id parameter.
 */
public interface LedgerRevenueApi {

	/**
	 * Every ledger entry in the caller's tenant created in {@code
	 * [fromInclusive, toExclusive)}, oldest first, each resolved to its
	 * course in one batched cross-module read (never N+1).
	 */
	List<LedgerRevenueEntry> findRevenueEntries(Instant fromInclusive, Instant toExclusive);

}
