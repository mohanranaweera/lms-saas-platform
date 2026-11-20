package com.lms.usermanagement.staff.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.staff.domain.StaffProfile;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}: every inherited finder
 * (findById, findAll, ...) is AND-combined with the resolved tenant context
 * by {@code TenantAwareRepositoryImpl} - no method here accepts a
 * caller-supplied {@code tenant_id}.
 */
public interface StaffProfileRepository extends TenantAwareRepository<StaffProfile, UUID> {

	/**
	 * STAFF-MGMT-02 server search: {@code term} (optional) matches first/last/full name or
	 * contact number case-insensitively (LIKE wildcards literal); profiles whose user id is in
	 * {@code userIdsMatchingEmail} also match. Tenant-scoped by the inherited {@code findAll}.
	 */
	default Page<StaffProfile> search(String term, Collection<UUID> userIdsMatchingEmail, Pageable pageable) {
		if (term == null || term.isBlank()) {
			return findAll(pageable);
		}
		String pattern = likePattern(term);
		return findAll((root, query, cb) -> {
			List<Predicate> any = new ArrayList<>();
			for (String field : List.of("firstName", "lastName", "name", "contactNumber")) {
				any.add(cb.like(cb.lower(root.get(field)), pattern, '\\'));
			}
			if (userIdsMatchingEmail != null && !userIdsMatchingEmail.isEmpty()) {
				any.add(root.get("userId").in(userIdsMatchingEmail));
			}
			return cb.or(any.toArray(Predicate[]::new));
		}, pageable);
	}

	/** Profiles whose name matches {@code term} (activity-feed actor search), at most {@code limit}. */
	default List<StaffProfile> findByNameContaining(String term, int limit) {
		if (term == null || term.isBlank()) {
			return List.of();
		}
		String pattern = likePattern(term);
		return findAll((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'),
				PageRequest.of(0, limit))
			.getContent();
	}

	default List<StaffProfile> findByUserIds(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("userId").in(userIds));
	}

	private static String likePattern(String term) {
		return "%" + term.strip()
			.toLowerCase(Locale.ROOT)
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_") + "%";
	}

}
