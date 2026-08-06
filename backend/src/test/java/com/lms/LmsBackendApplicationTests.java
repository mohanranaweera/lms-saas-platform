package com.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
// Same "test" profile every other integration test in this suite activates (see
// AbstractIntegrationTest) - without it this smoke test has no active profile at all, which
// JwtSecretStartupValidator (added for MVP-002) now correctly treats as a non-local/non-test
// deployment and fails fast on the placeholder JWT secret. This is not a real
// production-config check (app.security.jwt.secret is never overridden in the test classpath
// either way); it only verifies the context wires up, so it belongs in the same profile as
// every other test.
@ActiveProfiles("test")
class LmsBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
