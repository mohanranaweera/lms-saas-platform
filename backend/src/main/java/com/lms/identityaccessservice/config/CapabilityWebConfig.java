package com.lms.identityaccessservice.config;

import com.lms.identityaccessservice.api.PermissionCheckService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link CapabilityEnforcementInterceptor} for every API handler (STAFF-MGMT-02 §2.3).
 * The permission service is looked up lazily so {@code @WebMvcTest} slices (which pick up
 * {@code WebMvcConfigurer}s but not services) still start; the full application always has it.
 */
@Configuration
public class CapabilityWebConfig implements WebMvcConfigurer {

	private final ObjectProvider<PermissionCheckService> permissionCheckService;

	public CapabilityWebConfig(ObjectProvider<PermissionCheckService> permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		PermissionCheckService service = permissionCheckService.getIfAvailable();
		if (service != null) {
			registry.addInterceptor(new CapabilityEnforcementInterceptor(service)).addPathPatterns("/api/**");
		}
	}

}
