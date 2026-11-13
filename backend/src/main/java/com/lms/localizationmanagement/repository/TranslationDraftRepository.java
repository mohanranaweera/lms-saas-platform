package com.lms.localizationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.localizationmanagement.domain.TranslationDraft;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006: every finder is a {@code default} method over the inherited,
 * structurally tenant-filtered {@code findAll(Specification)} family; no method accepts a tenant
 * id. Never used outside {@code com.lms.localizationmanagement}.
 */
public interface TranslationDraftRepository extends TenantAwareRepository<TranslationDraft, UUID> {

	default Optional<TranslationDraft> findByLocaleAndKey(String locale, String messageKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("locale"), locale),
				cb.equal(root.get("messageKey"), messageKey)));
	}

	default List<TranslationDraft> findByLocale(String locale) {
		return findAll((root, query, cb) -> cb.equal(root.get("locale"), locale), Sort.by("messageKey"));
	}

}
