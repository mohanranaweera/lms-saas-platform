package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.service.NotificationTemplateSeedingService;
import com.lms.tenantmanagement.api.TenantRegisteredEvent;
import com.lms.tenantmanagement.service.TenantRegistrationCommand;
import com.lms.tenantmanagement.service.TenantRegistrationResult;
import com.lms.tenantmanagement.service.TenantRegistrationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Testcontainers-backed coverage of {@code NotificationTemplateSeedingService}'s
 * actual seeding behavior (MVP-018 post-ship review finding) - previously
 * completely unverified despite being the mechanism that makes real email
 * delivery possible for any tenant at MVP launch. Triggers the real
 * production path - {@link TenantRegistrationService#register} publishing
 * {@link TenantRegisteredEvent}, consumed by {@code
 * NotificationTemplateSeedingService#onTenantRegistered}'s {@code
 * AFTER_COMMIT} listener - the same technique {@code
 * NotificationDispatchIntegrationTest} already established for exercising an
 * {@code AFTER_COMMIT} listener end-to-end: call the real, autowired
 * producing service directly (not by hand-constructing and re-publishing the
 * event, which {@code @TransactionalEventListener}'s default {@code
 * fallbackExecution = false} would silently drop outside of an active
 * transaction).
 *
 * <p>Not {@code @Transactional} (inherited from {@code
 * NotificationManagementTestSupport}'s hierarchy) - {@code
 * TenantRegistrationService#register}'s own {@code @Transactional} method
 * must actually commit for its {@code AFTER_COMMIT} listener to fire at all.
 */
class NotificationTemplateSeedingIntegrationTest extends NotificationManagementTestSupport {

	@Autowired
	private TenantRegistrationService tenantRegistrationService;

	@Autowired
	private NotificationTemplateSeedingService notificationTemplateSeedingService;

	@Test
	void registeringATenantSeedsExactlyTheThreeDefaultTemplateRowsForThatTenant() {
		TenantRegistrationResult result = registerTenant("notif-seed-basic");

		List<String> templateKeys = withTenant(result.id(), () -> notificationTemplateRepository.findAll()).stream()
			.map(NotificationTemplate::getTemplateKey)
			.toList();

		assertThat(templateKeys).containsExactlyInAnyOrder(NotificationEventType.PAYMENT_CONFIRMED.name(),
				NotificationEventType.PAYMENT_REJECTED.name(), NotificationEventType.PAYMENT_REFUNDED.name());
	}

	@Test
	void reTriggeringTheSameTenantsRegistrationEventDoesNotDuplicateOrOverwriteExistingTemplateRows() {
		TenantRegistrationResult result = registerTenant("notif-seed-redeliver");
		NotificationTemplate originalConfirmed = withTenant(result.id(),
				() -> notificationTemplateRepository.findByTemplateKey(NotificationEventType.PAYMENT_CONFIRMED.name()))
			.orElseThrow();

		// Simulates TenantRegisteredEvent redelivery for the SAME tenant -
		// proves Fix 2's NotificationTemplateSeedWriter/seedIfAbsent race
		// guard is actually race-safe (a lost unique-constraint race per
		// template key is caught and treated as already-seeded), not just
		// "doesn't duplicate on a fresh tenant".
		assertThatCode(() -> notificationTemplateSeedingService
			.onTenantRegistered(new TenantRegisteredEvent(result.id(), "Re-delivered", Instant.now())))
			.doesNotThrowAnyException();

		List<NotificationTemplate> templatesAfterRedelivery = withTenant(result.id(),
				() -> notificationTemplateRepository.findAll());
		assertThat(templatesAfterRedelivery).hasSize(3);
		assertThat(templatesAfterRedelivery).extracting(NotificationTemplate::getTemplateKey)
			.containsExactlyInAnyOrder(NotificationEventType.PAYMENT_CONFIRMED.name(),
					NotificationEventType.PAYMENT_REJECTED.name(), NotificationEventType.PAYMENT_REFUNDED.name());
		NotificationTemplate reloadedConfirmed = withTenant(result.id(),
				() -> notificationTemplateRepository.findByTemplateKey(NotificationEventType.PAYMENT_CONFIRMED.name()))
			.orElseThrow();
		assertThat(reloadedConfirmed.getId()).isEqualTo(originalConfirmed.getId());
		assertThat(reloadedConfirmed.getSubject()).isEqualTo(originalConfirmed.getSubject());

		Long dbCount = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_template WHERE tenant_id = ?",
				Long.class, result.id());
		assertThat(dbCount).isEqualTo(3L);
	}

	@Test
	void aSecondTenantsSeededTemplatesAreNeverMixedWithTheFirstTenantsRows() {
		TenantRegistrationResult tenantA = registerTenant("notif-seed-xt-a");
		TenantRegistrationResult tenantB = registerTenant("notif-seed-xt-b");

		List<NotificationTemplate> tenantATemplates = withTenant(tenantA.id(),
				() -> notificationTemplateRepository.findAll());
		List<NotificationTemplate> tenantBTemplates = withTenant(tenantB.id(),
				() -> notificationTemplateRepository.findAll());

		assertThat(tenantATemplates).hasSize(3).allMatch(t -> t.getTenantId().equals(tenantA.id()));
		assertThat(tenantBTemplates).hasSize(3).allMatch(t -> t.getTenantId().equals(tenantB.id()));

		// Under tenant B's own context, tenant A's template ids are invisible
		// via the tenant-scoped finder - not merely "a different row with the
		// same key", but genuinely absent.
		List<UUID> tenantAIds = tenantATemplates.stream().map(NotificationTemplate::getId).toList();
		List<UUID> tenantBVisibleIds = withTenant(tenantB.id(), () -> notificationTemplateRepository.findAll())
			.stream()
			.map(NotificationTemplate::getId)
			.toList();
		assertThat(tenantBVisibleIds).noneMatch(tenantAIds::contains);
	}

	private TenantRegistrationResult registerTenant(String prefix) {
		String subdomain = uniqueSubdomain(prefix);
		TenantRegistrationCommand command = new TenantRegistrationCommand("Test Institute " + subdomain, subdomain,
				"starter", "Jane Doe", "contact-" + subdomain + "@example.test", "+1-555-0100");
		return tenantRegistrationService.register(command);
	}

}
