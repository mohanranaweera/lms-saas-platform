package com.lms.integrationmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.TenantMediaIntegrationSettings;
import com.lms.integrationmanagement.repository.TenantMediaIntegrationSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current tenant's integration switches (V60). A tenant with no row gets
 * the defaults (all teacher providers enabled, no video host) without a row
 * being written until an admin changes something.
 */
@Service
public class TenantMediaSettingsService {

	private final TenantMediaIntegrationSettingsRepository repository;

	private final TenantContext tenantContext;

	public TenantMediaSettingsService(TenantMediaIntegrationSettingsRepository repository,
			TenantContext tenantContext) {
		this.repository = repository;
		this.tenantContext = tenantContext;
	}

	/** Read-only; an unsaved default instance when the tenant has no row. */
	@Transactional(readOnly = true)
	public TenantMediaIntegrationSettings current() {
		return repository.findForCurrentTenant()
			.orElseGet(() -> new TenantMediaIntegrationSettings(tenantContext.getTenantId()));
	}

	@Transactional(readOnly = true)
	public boolean isEnabled(MediaProvider provider) {
		return current().isEnabled(provider);
	}

	/** Must be called inside the caller's write transaction. */
	TenantMediaIntegrationSettings currentForUpdate() {
		return current();
	}

	TenantMediaIntegrationSettings save(TenantMediaIntegrationSettings settings) {
		return repository.saveAndFlush(settings);
	}

}
