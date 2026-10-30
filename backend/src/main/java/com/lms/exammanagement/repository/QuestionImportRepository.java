package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.QuestionImport;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Tenant-scoped ({@code TenantAwareRepository}): every finder is a Specification-backed
 * default method, so the structural tenant predicate is always applied. Never referenced
 * outside {@code com.lms.exammanagement}.
 */
public interface QuestionImportRepository extends TenantAwareRepository<QuestionImport, UUID> {

	default Optional<QuestionImport> findByModuleIdAndIdempotencyKey(UUID moduleId, String idempotencyKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("moduleId"), moduleId),
				cb.equal(root.get("idempotencyKey"), idempotencyKey)));
	}

	/** Newest first; {@code search} matches the file name case-insensitively (LIKE-escaped). */
	default Page<QuestionImport> findByModuleId(UUID moduleId, String search, Pageable pageable) {
		Specification<QuestionImport> spec = (root, query, cb) -> cb.equal(root.get("moduleId"), moduleId);
		if (search != null && !search.isBlank()) {
			String pattern = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("fileName")), pattern, '\\'));
		}
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				Sort.by(Sort.Order.desc("importedAt"), Sort.Order.desc("id")));
		return findAll(spec, sorted);
	}

	private static String escapeLike(String raw) {
		return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
