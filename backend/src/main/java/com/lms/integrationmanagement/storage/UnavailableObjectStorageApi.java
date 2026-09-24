package com.lms.integrationmanagement.storage;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import java.time.Duration;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Default {@link ObjectStorageApi} bean - active whenever no real object
 * storage provider has been configured (see {@link
 * ObjectStorageNotConfiguredCondition}/{@code object-storage.bucket}).
 * Deliberately fails loudly with a client-safe 503 rather than silently
 * succeeding against a fake/local implementation - {@code
 * .claude/rules/architecture.md} forbids self-hosted binary media storage
 * through the app tier, so there is no safe local fallback to offer instead.
 * This is the fail-closed default for every consuming domain (currently
 * {@code content-management} and {@code video-access-management}/{@code
 * payment-management}) whenever {@link S3ObjectStorageApi} is not active.
 */
@Component
@Conditional(ObjectStorageNotConfiguredCondition.class)
public class UnavailableObjectStorageApi implements ObjectStorageApi {

	// Client-safe by design (see class javadoc) but must also read as calm,
	// expected copy to a real Teacher/Student/reviewer, not an internal-
	// configuration complaint - this is currently the *only* reachable
	// outcome of every upload/download-url call in every environment, so its
	// wording is what every real user of this feature sees today.
	private static final String MESSAGE = "Uploads aren't available right now. Please try again later.";

	@Override
	public StoredObject store(StoreObjectCommand command) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public void delete(String objectKey) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl) {
		throw new ServiceUnavailableException(MESSAGE);
	}

}
