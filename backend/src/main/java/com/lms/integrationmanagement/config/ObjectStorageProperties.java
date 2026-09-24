package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S3-compatible object storage configuration, sourced the same
 * env-var-backed {@code @ConfigurationProperties} way as {@link
 * PaymentGatewayProperties} sources {@code PAYMENT_GATEWAY_WEBHOOK_SECRET}:
 * every field defaults to blank so an unconfigured environment fails closed
 * rather than silently pointing at a well-known/guessable endpoint.
 *
 * <p>{@code bucket}, {@code accessKey}, AND {@code secretKey} together are
 * the "is a real provider configured" signal used by {@code
 * com.lms.integrationmanagement.storage} to choose between {@link
 * com.lms.integrationmanagement.storage.S3ObjectStorageApi} (all three set)
 * and {@link com.lms.integrationmanagement.storage.UnavailableObjectStorageApi}
 * (any one blank, the fail-closed default) - see {@code
 * ObjectStorageConfiguredCondition}'s javadoc for why checking only {@code
 * bucket} was insufficient. Real (non-local) environments MUST set {@code
 * OBJECT_STORAGE_ENDPOINT},
 * {@code OBJECT_STORAGE_BUCKET}, {@code OBJECT_STORAGE_ACCESS_KEY}, and
 * {@code OBJECT_STORAGE_SECRET_KEY}; local dev points these at the
 * already-provisioned {@code docker-compose.dev.yml} MinIO service (see
 * {@code application-local.yml}).
 */
@Component
@ConfigurationProperties(prefix = "object-storage")
public class ObjectStorageProperties {

	private String endpoint = "";

	private String region = "us-east-1";

	private String bucket = "";

	private String accessKey = "";

	private String secretKey = "";

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

}
