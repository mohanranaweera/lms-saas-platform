package com.lms.contentmanagement;

import com.lms.contentmanagement.material.storage.ObjectStorageApi;
import com.lms.contentmanagement.material.storage.SignedDownloadUrl;
import com.lms.contentmanagement.material.storage.StoreObjectCommand;
import com.lms.contentmanagement.material.storage.StoredObject;
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
 * Test-only {@link ObjectStorageApi} stand-in for the {@code
 * content-management} Testcontainers integration tests. The real Spring
 * context binds {@code UnavailableObjectStorageApi} (throws {@code
 * ServiceUnavailableException}/503 on every call, since {@code
 * integration-management} does not exist yet - see that class's own
 * javadoc), which is correct for production but would make every
 * upload/download/delete integration test fail before exercising the
 * behavior under test. This {@code @Primary} in-memory bean overrides it for
 * tests only (never wired into {@code src/main}), backed by a simple {@code
 * ConcurrentHashMap} keyed by a random UUID object key per {@link #store}
 * call - just enough fidelity to prove {@code MaterialService}'s own
 * orchestration (size/content-sniffing gates, sequence computation,
 * delete-then-audit-event flow) end-to-end over real HTTP/Postgres.
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
	 * method above specifically so H1's/M5's integration tests (in other
	 * packages, e.g. {@code com.lms.contentmanagement.material.web}) can
	 * {@code @Autowire InMemoryObjectStorageApi} directly and inspect {@link
	 * #containsKey} after a real HTTP delete or a rolled-back transaction,
	 * proving the {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
	 * in {@code MaterialService#onMaterialDeleted} actually ran (or did not
	 * run) {@code ObjectStorageApi#delete} against a genuinely
	 * committed/rolled-back transaction - not just that the {@code material}
	 * row disappeared from Postgres (the pre-existing tests' only proof).
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
		 * {@code store} call to recover the generated key {@link
		 * com.lms.contentmanagement.material.web.dto.MaterialResponse}
		 * deliberately never exposes (per this module's protected-content
		 * security design).
		 */
		public Set<String> keySet() {
			return Set.copyOf(objects.keySet());
		}

	}

}
