package com.lms.financeexpensemanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.financeexpensemanagement.repository.FinanceScheduledTransactionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FIN-01: materialises due scheduled occurrences for every tenant that has an
 * active schedule. It never posts money (see {@link FinanceScheduleService}
 * policy). Tenant identity is carried explicitly: the job reads the tenant ids
 * through the one named cross-tenant query, then sets the tenant context per
 * tenant before touching any tenant-owned row. Idempotent - a duplicate run
 * creates nothing new (unique due dates).
 */
@Component
public class FinanceScheduleJob {

	private static final Logger log = LoggerFactory.getLogger(FinanceScheduleJob.class);

	private final FinanceScheduledTransactionRepository scheduleRepository;

	private final FinanceScheduleService scheduleService;

	public FinanceScheduleJob(FinanceScheduledTransactionRepository scheduleRepository,
			FinanceScheduleService scheduleService) {
		this.scheduleRepository = scheduleRepository;
		this.scheduleService = scheduleService;
	}

	@Scheduled(cron = "${app.finance.schedules.materialize-cron:0 20 0 * * *}")
	public void materializeAll() {
		List<UUID> tenantIds = scheduleRepository.findTenantIdsWithActiveSchedulesAcrossTenants();
		for (UUID tenantId : tenantIds) {
			TenantContextHolder.set(tenantId);
			try {
				int created = scheduleService.materializeDueForCurrentTenant();
				if (created > 0) {
					log.info("finance.schedule_occurrences_materialized tenantId={} created={}", tenantId, created);
				}
			}
			catch (RuntimeException ex) {
				log.error("finance.schedule_materialize_failed tenantId={}", tenantId, ex);
			}
			finally {
				TenantContextHolder.clear();
			}
		}
	}

}
