package com.lms.common.config;

import com.lms.common.persistence.TenantAwareRepositoryFactoryBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers {@link TenantAwareRepositoryFactoryBean} as the factory for
 * every repository under {@code com.lms}. This replaces (not supplements)
 * Spring Boot's default JPA repository auto-configuration, per Spring Data's
 * documented back-off behavior once an explicit
 * {@code @EnableJpaRepositories} is present.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.lms", repositoryFactoryBeanClass = TenantAwareRepositoryFactoryBean.class)
public class JpaRepositoryConfig {

}
