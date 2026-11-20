package com.lms.tenantmanagement.api;

/**
 * The value shape of a {@link StudentProfileField} (STU-PROF-01). Drives both
 * server-side value validation ({@code user-management}) and which input the
 * frontend renders.
 */
public enum StudentProfileFieldType {

	/** Free text, trimmed, bounded by {@link StudentProfileField#maxLength()}. */
	TEXT,

	/** A phone number: optional leading {@code +}, digits, spaces, brackets, dashes. */
	PHONE,

	/** An ISO {@code yyyy-MM-dd} date, not in the future. */
	DATE,

	/** A four-digit year. */
	YEAR,

	/** Yes/no. */
	BOOLEAN,

	/** An ISO-3166 alpha-2 country code. */
	COUNTRY,

	/** A composite of guardian name + guardian phone. */
	GUARDIAN

}
