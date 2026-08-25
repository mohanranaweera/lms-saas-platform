package com.lms.integrationmanagement;

import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only {@link ObjectStorageApi} stand-in shared by every domain that
 * depends on this port ({@code content-management}'s material upload,
 * {@code payment-management}'s slip upload). The real Spring context binds
 * {@code integrationmanagement.storage.UnavailableObjectStorageApi} (throws
 * {@code ServiceUnavailableException}/503 on every call, since no object
 * storage provider is integrated yet - see that class's own javadoc), which
 * is correct for production but would make every upload/download/delete
 * integration test fail before exercising the behavior under test. This
 * {@code @Primary} in-memory bean overrides it for tests only (never wired
 * into {@code src/main}), backed by a simple {@code ConcurrentHashMap} keyed
 * by a random UUID object key per {@link #store} call - just enough fidelity
 * to prove each consuming domain's own orchestration (size/content-sniffing
 * gates, delete-then-audit-event flow, etc.) end-to-end over real
 * HTTP/Postgres.
 *
 * <p>Deliberately a single, neutral, both-sides-importable location (under
 * {@code com.lms.integrationmanagement}, not under either consuming domain's
 * test package) - both {@code ContentManagementTestSupport} and {@code
 * PaymentManagementTestSupport}/{@code SlipTestSupport} {@code @Import} this
 * same config, rather than each domain maintaining its own module-local
 * fake.
 *
 * <p><b>L4 - shared, unreset {@code @Primary} singleton, deliberately:</b>
 * the backing {@code ConcurrentHashMap} is a single {@code @Primary} bean
 * shared by every test class in the same Spring test-context cache (both
 * {@code content-management} and {@code payment-management} tests import
 * this same config), and it is intentionally never reset/cleared between
 * individual tests or test classes - there is no {@code @BeforeEach}/{@code
 * @AfterEach} hook here or in any consumer that empties it. This is safe
 * ONLY because every current consumer ({@code
 * SlipUploadIntegrationTest#theSlipFileIsNeverReachableViaADirectOrPredictableUrl}
 * and {@code MaterialDeletionIntegrationTest}'s storage-cleanup tests) reads
 * {@link #keySet()} (or {@link #containsKey(String)} for a key it already
 * captured) diffed against a "keys before my own {@link #store} call"
 * snapshot, rather than assuming anything about the map's total size or that
 * it starts empty. Any future test that needs an absolute-count assertion
 * (e.g. "exactly one object exists") or a "the store is empty at test start"
 * assumption must NOT rely on this bean being empty - it will already
 * contain objects left behind by every earlier test in the same context
 * cache. Such a test must instead diff key sets the same way the existing
 * consumers do, or introduce its own explicit reset mechanism.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemoryObjectStorageApiTestConfig {

	@Bean
	@Primary
	public InMemoryObjectStorageApi inMemoryObjectStorageApi() {
		return new InMemoryObjectStorageApi();
	}

	/**
	 * {@code public} (not {@code private}) and typed as the concrete class -
	 * rather than the {@link ObjectStorageApi} interface - on the {@code @Bean}
	 * method above specifically so integration tests in other packages can
	 * {@code @Autowire InMemoryObjectStorageApi} directly and inspect {@link
	 * #containsKey}/{@link #keySet} after a real HTTP delete or a rolled-back
	 * transaction, proving a {@code @TransactionalEventListener(phase =
	 * AFTER_COMMIT)} actually ran (or did not run) {@code
	 * ObjectStorageApi#delete} against a genuinely committed/rolled-back
	 * transaction - not just that a row disappeared from Postgres.
	 */
	public static final class InMemoryObjectStorageApi implements ObjectStorageApi {

		private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

		@Override
		public StoredObject store(StoreObjectCommand command) {
			String objectKey = UUID.randomUUID().toString();
			try {
				objects.put(objectKey, command.content().readAllBytes());
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return new StoredObject(objectKey, command.sizeBytes());
		}

		@Override
		public void delete(String objectKey) {
			objects.remove(objectKey);
		}

		@Override
		public SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl) {
			return new SignedDownloadUrl("https://test-object-storage.invalid/" + objectKey, Instant.now().plus(ttl));
		}

		/** Test-only observability: is this object key still present in the in-memory backing store? */
		public boolean containsKey(String objectKey) {
			return objects.containsKey(objectKey);
		}

		/**
		 * Test-only observability: a snapshot of every object key currently
		 * held, for tests that need to diff "keys before" vs "keys after" a
		 * {@code store} call to recover the generated key a response DTO
		 * deliberately never exposes (per each consuming domain's
		 * protected-content security design).
		 */
		public Set<String> keySet() {
			return Set.copyOf(objects.keySet());
		}

	}

}
