package com.lms.usermanagement.student.service;

import java.util.UUID;

/**
 * One row's outcome from {@link StudentBulkImportService#importCsv} (Wave 3
 * - Bulk CSV import). Per-row partial-failure semantics: every row is
 * attempted independently, so a batch response mixes {@code CREATED} and
 * {@code FAILED} rows - never all-or-nothing (an explicit judgment call,
 * per the wave-03 plan §4/§10 item 1).
 */
public record BulkImportRowResult(int row, String status, String reason, UUID studentId) {

	public static final String CREATED = "CREATED";

	public static final String FAILED = "FAILED";

	public static BulkImportRowResult created(int row, UUID studentId) {
		return new BulkImportRowResult(row, CREATED, null, studentId);
	}

	public static BulkImportRowResult failed(int row, String reason) {
		return new BulkImportRowResult(row, FAILED, reason, null);
	}

}
