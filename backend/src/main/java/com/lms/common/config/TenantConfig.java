package com.lms.common.config;

import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TenantConfig {

	@Bean
	public TenantContext tenantContext() {
		return new TenantContextHolder();
	}

}
