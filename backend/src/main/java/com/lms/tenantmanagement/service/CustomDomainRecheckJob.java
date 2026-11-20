package com.lms.tenantmanagement.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TEN-DOM-01 / ADR-021 decision 2: daily DNS re-check of custom domains
 * ({@link CustomDomainService#recheckAll}). Idempotent, so running on more
 * than one instance only repeats lookups.
 */
@Component
public class CustomDomainRecheckJob {

	private final CustomDomainService customDomainService;

	public CustomDomainRecheckJob(CustomDomainService customDomainService) {
		this.customDomainService = customDomainService;
	}

	@Scheduled(cron = "${app.custom-domains.recheck-cron:0 17 3 * * *}")
	public void recheck() {
		customDomainService.recheckAll();
	}

}
