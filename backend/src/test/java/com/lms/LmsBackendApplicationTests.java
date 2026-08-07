package com.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
// Same "test" profile every other integration test in this suite activates (see
// AbstractIntegrationTest), for consistency. Note JwtSecretStartupValidator does NOT run here
// regardless of profile: @SpringBootTest bootstraps the context directly rather than through
// LmsBackendApplication#main, and that validator is deliberately registered as an
// ApplicationContextInitializer from main (not a @Component/ApplicationListener) so it runs
// before the embedded web server starts - see JwtSecretStartupValidatorTest for its actual
// coverage.
@ActiveProfiles("test")
class LmsBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
