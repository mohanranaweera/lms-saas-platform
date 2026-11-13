package com.lms.localizationmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.localizationmanagement.domain.TranslationReleaseEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Append-only and tenant-scoped (ADR-006): no delete, no update path, no tenant-id parameter.
 * Never used outside {@code com.lms.localizationmanagement}.
 */
public interface TranslationReleaseEntryRepository
		extends AppendOnlyTenantAwareRepository<TranslationReleaseEntry, UUID> {

	default List<TranslationReleaseEntry> findByReleaseId(UUID releaseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("releaseId"), releaseId), Sort.by("messageKey"));
	}

}
