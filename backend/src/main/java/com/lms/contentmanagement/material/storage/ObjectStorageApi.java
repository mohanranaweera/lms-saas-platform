package com.lms.contentmanagement.material.storage;

import java.time.Duration;

/**
 * The narrow port content-management depends on for external object
 * storage, mirroring the shape a future {@code integration-management}
 * domain is expected to provide (see {@code docs/plans/MVP-009 Lessons and
 * Learning Materials.md} §9). {@code integration-management} does not exist
 * anywhere in this codebase yet (verified: no {@code
 * com.lms.integrationmanagement} package on any branch) - this interface is
 * defined HERE, inside content-management, rather than under a fabricated
 * {@code com.lms.integrationmanagement} package, because this module must
 * not invent a new top-level domain package on that domain's behalf (that
 * would need its own architecture/ADR sign-off, per {@code
 * .claude/rules/architecture.md}). Once {@code integration-management} is
 * actually built, its real {@code api} package should either implement this
 * same interface directly or this interface should move there - a small,
 * deliberate follow-up, not something this module resolves unilaterally.
 */
public interface ObjectStorageApi {

	StoredObject store(StoreObjectCommand command);

	void delete(String objectKey);

	SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl);

}
