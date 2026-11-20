package com.lms.integrationmanagement.domain;

/** Stored lifecycle of a teacher's Bunny library row (MEDIA-INT-01A, V61). */
public enum BunnyLibraryStatus {

	/** Row committed, Bunny call in progress (or interrupted - retry adopts the library by tag). */
	PROVISIONING,

	/** The Bunny library exists and is recorded. */
	ACTIVE,

	/** The Bunny call failed; kept as history. A retry inserts a new row. */
	FAILED

}
