package com.lms;

import com.lms.identityaccessservice.config.JwtSecretStartupValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LmsBackendApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(LmsBackendApplication.class);
		// Registered as an initializer (not a @Component) so it runs before the
		// embedded web server starts accepting connections - see
		// JwtSecretStartupValidator's own javadoc for why ApplicationReadyEvent is
		// too late for this specific check.
		application.addInitializers(new JwtSecretStartupValidator());
		application.run(args);
	}

}
