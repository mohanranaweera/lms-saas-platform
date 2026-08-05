package com.lms.common;

import com.lms.TestcontainersConfiguration;
import com.lms.common.tenant.TenantContextHolder;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared base for Testcontainers-backed integration tests. Reuses the
 * existing {@link TestcontainersConfiguration} (Postgres 17-alpine, Redis
 * 7-alpine) wiring. Future domain modules should extend this rather than
 * re-declaring container setup per module.
 *
 * Clears {@link TenantContextHolder} before and after every test so no
 * tenant id leaks between tests sharing a pooled thread.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	protected static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	protected static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	@org.springframework.beans.factory.annotation.Autowired
	protected TestRestTemplate restTemplate;

	@BeforeEach
	void clearTenantContextBefore() {
		TenantContextHolder.clear();
	}

	@AfterEach
	void clearTenantContextAfter() {
		TenantContextHolder.clear();
	}

	protected <T> T withTenant(UUID tenantId, Supplier<T> action) {
		TenantContextHolder.set(tenantId);
		try {
			return action.get();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	protected void withTenant(UUID tenantId, Runnable action) {
		TenantContextHolder.set(tenantId);
		try {
			action.run();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
