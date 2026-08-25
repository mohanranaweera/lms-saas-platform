package com.lms.integrationmanagement.api;

import java.time.Duration;

/**
 * The narrow port other domains depend on for external object storage (e.g.
 * {@code content-management}'s lesson materials, {@code payment-management}'s
 * payment slip evidence). {@code integration-management} owns all
 * third-party credentials/webhooks - including object storage - per {@code
 * .claude/rules/architecture.md}; other domains call this {@code api}
 * interface rather than embedding a provider SDK/credentials directly.
 */
public interface ObjectStorageApi {

	StoredObject store(StoreObjectCommand command);

	void delete(String objectKey);

	SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl);

}
