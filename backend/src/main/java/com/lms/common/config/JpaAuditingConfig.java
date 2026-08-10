package com.lms.common.config;

import com.lms.common.persistence.AuditorAwareImpl;
import com.lms.common.persistence.CurrentActorProvider;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

	@Bean
	public AuditorAware<UUID> auditorAware(ObjectProvider<CurrentActorProvider> actorProvider) {
		return new AuditorAwareImpl(actorProvider);
	}

}
