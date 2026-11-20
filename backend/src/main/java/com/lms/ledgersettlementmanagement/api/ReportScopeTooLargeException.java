package com.lms.ledgersettlementmanagement.api;

/**
 * RPT-01: thrown by {@link TeacherIncomeLedgerApi} when the number of ledger
 * entries in the requested scope (counted BEFORE any enrichment) exceeds the
 * caller's limit. The caller maps it to its own client error.
 */
public class ReportScopeTooLargeException extends RuntimeException {

	private final long entryCount;

	private final long maxEntries;

	public ReportScopeTooLargeException(long entryCount, long maxEntries) {
		super("Report scope has " + entryCount + " ledger entries; the limit is " + maxEntries);
		this.entryCount = entryCount;
		this.maxEntries = maxEntries;
	}

	public long getEntryCount() {
		return entryCount;
	}

	public long getMaxEntries() {
		return maxEntries;
	}

}
