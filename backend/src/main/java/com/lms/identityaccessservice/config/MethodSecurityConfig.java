package com.lms.identityaccessservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables Spring Security method security so {@code @PreAuthorize} on a
 * controller method can call {@code @permissionCheckService.hasPermission(...)}
 * by bean name via SpEL (RBAC-2, plan §9). Spring Security's default
 * method-security expression handler already supports {@code
 * @beanName.method(...)} SpEL without any custom {@code PermissionEvaluator}
 * - no extra dependency is needed beyond the existing {@code
 * spring-boot-starter-security}, and this deliberately does not add {@code
 * spring-boot-starter-aop}/AspectJ.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

}
