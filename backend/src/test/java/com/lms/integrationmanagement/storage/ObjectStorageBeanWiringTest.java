package com.lms.integrationmanagement.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.config.ObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the conditional-bean selection between {@link S3ObjectStorageApi}
 * and {@link UnavailableObjectStorageApi} (Wave 5, wave-05-plan §4/§9):
 * exactly one {@link ObjectStorageApi} bean is ever active, chosen by
 * whether {@code object-storage.bucket}, {@code object-storage.access-key},
 * AND {@code object-storage.secret-key} are ALL non-blank (security-review
 * fix - see {@link ObjectStorageConfiguredCondition}'s javadoc: {@code
 * bucket} alone used to be sufficient, which let a partially-configured
 * environment select the real {@link S3ObjectStorageApi} bean instead of
 * failing closed) - mirroring how a payment-gateway-style
 * conditional-bean-selection test would be structured in this codebase
 * (isolated {@link ApplicationContextRunner}, no full {@code
 * @SpringBootTest}/Testcontainers needed since neither bean makes a network
 * call at construction time).
 */
class ObjectStorageBeanWiringTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class, UnavailableObjectStorageApi.class, S3ObjectStorageApi.class);

	@Test
	void unavailableAdapterIsActiveWhenBucketIsBlank() {
		contextRunner.withPropertyValues("object-storage.bucket=").run(context -> {
			assertThat(context).hasSingleBean(ObjectStorageApi.class);
			assertThat(context.getBean(ObjectStorageApi.class)).isInstanceOf(UnavailableObjectStorageApi.class);
		});
	}

	@Test
	void unavailableAdapterIsActiveWhenBucketIsAbsentEntirely() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(ObjectStorageApi.class);
			assertThat(context.getBean(ObjectStorageApi.class)).isInstanceOf(UnavailableObjectStorageApi.class);
		});
	}

	/**
	 * Security-review fix regression test: a partially-configured
	 * environment (bucket set, but access/secret key left blank) must still
	 * fail closed to {@link UnavailableObjectStorageApi} - the exact gap
	 * {@link ObjectStorageConfiguredCondition}'s bucket-alone check used to
	 * leave open.
	 */
	@Test
	void unavailableAdapterIsActiveWhenBucketIsSetButAccessAndSecretKeyAreBlank() {
		contextRunner.withPropertyValues("object-storage.bucket=lms-test-bucket").run(context -> {
			assertThat(context).hasSingleBean(ObjectStorageApi.class);
			assertThat(context.getBean(ObjectStorageApi.class)).isInstanceOf(UnavailableObjectStorageApi.class);
		});
	}

	@Test
	void s3AdapterIsActiveWhenBucketIsConfigured() {
		contextRunner
			.withPropertyValues("object-storage.bucket=lms-test-bucket", "object-storage.region=us-east-1",
					"object-storage.access-key=test-access-key", "object-storage.secret-key=test-secret-key",
					"object-storage.endpoint=http://localhost:9000")
			.run(context -> {
				assertThat(context).hasSingleBean(ObjectStorageApi.class);
				assertThat(context.getBean(ObjectStorageApi.class)).isInstanceOf(S3ObjectStorageApi.class);
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ObjectStorageProperties.class)
	static class PropertiesConfig {

	}

}
