package com.lms.integrationmanagement.storage;

import com.lms.integrationmanagement.config.ObjectStorageProperties;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 SDK client + presigner pair from {@code object-storage.*}
 * for every S3 adapter in this package ({@link S3ObjectStorageApi}, {@link
 * S3MediaStagingApi}), so endpoint/path-style handling stays identical.
 */
final class S3Clients {

	record Pair(S3Client client, S3Presigner presigner) {

	}

	private S3Clients() {
	}

	static Pair create(ObjectStorageProperties properties) {
		Region region = Region.of(properties.getRegion());
		StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));

		S3ClientBuilder clientBuilder = S3Client.builder()
			.region(region)
			.credentialsProvider(credentialsProvider)
			// Path-style access is required for MinIO (and most non-AWS
			// S3-compatible endpoints) - virtual-hosted-style bucket
			// addressing (the SDK's default) only works against real AWS.
			.forcePathStyle(true);
		S3Presigner.Builder presignerBuilder = S3Presigner.builder()
			.region(region)
			.credentialsProvider(credentialsProvider)
			// S3Presigner has no forcePathStyle() shorthand (unlike
			// S3ClientBuilder) - path-style access is set via
			// S3Configuration instead, so presigned URLs resolve against
			// MinIO/non-AWS endpoints exactly like the client above.
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

		if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
			URI endpointUri = URI.create(properties.getEndpoint());
			clientBuilder.endpointOverride(endpointUri);
			presignerBuilder.endpointOverride(endpointUri);
		}
		return new Pair(clientBuilder.build(), presignerBuilder.build());
	}

}
