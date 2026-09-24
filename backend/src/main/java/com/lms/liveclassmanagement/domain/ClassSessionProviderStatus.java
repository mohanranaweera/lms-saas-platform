package com.lms.liveclassmanagement.domain;

/**
 * {@code class_session.provider_status} (V49's {@code
 * ck_class_session_provider_status} CHECK constraint) - independent of
 * {@link ClassSessionStatus}. A provider failure never deletes/hides a
 * session; it stays {@code SCHEDULED} (or whatever its current lifecycle
 * status is) with {@code providerStatus == FAILED}, retryable via {@link
 * ClassSession#markProvisioned}/{@link ClassSession#markProvisioningFailed}.
 */
public enum ClassSessionProviderStatus {

	PENDING, PROVISIONED, FAILED

}
