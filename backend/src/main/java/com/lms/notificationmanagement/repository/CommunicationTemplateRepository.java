package com.lms.notificationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.notificationmanagement.domain.CommunicationTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-filtered access to {@code communication_template} (Wave 11).
 * Templates are archived, never deleted (broadcasts reference them).
 */
public interface CommunicationTemplateRepository extends TenantAwareRepository<CommunicationTemplate, UUID> {

	default List<CommunicationTemplate> findAllByStatus(CommunicationTemplate.Status status) {
		return findAll((root, query, cb) -> cb.equal(root.get("status"), status), Sort.by("name"));
	}

	/** Case-insensitive name clash among ACTIVE templates, optionally excluding one id (for an edit). */
	default boolean existsActiveName(String name, UUID excludeId) {
		return exists((root, query, cb) -> {
			var predicate = cb.and(cb.equal(cb.lower(root.get("name")), name.toLowerCase(java.util.Locale.ROOT)),
					cb.equal(root.get("status"), CommunicationTemplate.Status.ACTIVE));
			return (excludeId == null) ? predicate : cb.and(predicate, cb.notEqual(root.get("id"), excludeId));
		});
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("templates are archived, never deleted");
	}

	@Override
	default void delete(CommunicationTemplate entity) {
		throw new UnsupportedOperationException("templates are archived, never deleted");
	}

}
