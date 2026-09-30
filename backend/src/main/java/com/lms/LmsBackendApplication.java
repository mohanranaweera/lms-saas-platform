package com.lms;

import com.lms.common.config.ProductionSecretsStartupValidator;
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
		// too late for this specific check. The production secrets check (ADR-019)
		// runs first so a misconfigured 'prod' deployment reports every missing
		// secret at once.
		application.addInitializers(new ProductionSecretsStartupValidator(), new JwtSecretStartupValidator());
		application.run(args);
	}

}
