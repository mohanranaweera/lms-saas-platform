package com.lms.localizationmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.localizationmanagement.domain.TranslationRelease;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Append-only and tenant-scoped (ADR-006): no delete, no update path, no tenant-id parameter.
 * Never used outside {@code com.lms.localizationmanagement}.
 */
public interface TranslationReleaseRepository extends AppendOnlyTenantAwareRepository<TranslationRelease, UUID> {

	/** The current release of {@code locale} (highest {@code version_no}), if any. */
	default Optional<TranslationRelease> findLatest(String locale) {
		return findAll((root, query, cb) -> cb.equal(root.get("locale"), locale),
				PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "versionNo")))
			.stream()
			.findFirst();
	}

	/** Every release of {@code locale}, newest first. */
	default List<TranslationRelease> findByLocale(String locale) {
		return findAll((root, query, cb) -> cb.equal(root.get("locale"), locale),
				Sort.by(Sort.Direction.DESC, "versionNo"));
	}

}
