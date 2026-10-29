package com.lms.contentmanagement.library.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.domain.MediaFileUsage;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Tenant-scoped per ADR-006: every finder is a {@link Specification}-backed
 * default method, so {@code TenantAwareRepositoryImpl} ANDs in the tenant
 * predicate from the resolved {@code TenantContext}. No method accepts a
 * tenant id. "Live" = not soft-deleted.
 */
public interface MediaFileRepository extends TenantAwareRepository<MediaFile, UUID> {

	default Optional<MediaFile> findLiveById(UUID id) {
		return findOne(byId(id).and(live()));
	}

	/** A live Media Library file (COURSE_LIBRARY); site files such as teacher photos are "not found" here. */
	default Optional<MediaFile> findLiveLibraryById(UUID id) {
		return findOne(byId(id).and(live()).and(library()));
	}

	default Page<MediaFile> findLive(MediaFileKind kind, String search, Pageable pageable) {
		Specification<MediaFile> spec = live().and(library());
		if (kind != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
		}
		if (search != null && !search.isBlank()) {
			String pattern = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
					cb.like(cb.lower(root.get("originalFilename")), pattern, '\\')));
		}
		return findAll(spec, pageable);
	}

	default long countLiveByKind(MediaFileKind kind) {
		return count(live().and(library()).and((root, query, cb) -> cb.equal(root.get("kind"), kind)));
	}

	private static Specification<MediaFile> byId(UUID id) {
		return (root, query, cb) -> cb.equal(root.get("id"), id);
	}

	private static Specification<MediaFile> library() {
		return (root, query, cb) -> cb.equal(root.get("usageArea"), MediaFileUsage.COURSE_LIBRARY);
	}

	private static Specification<MediaFile> live() {
		return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
	}

	private static String escapeLike(String raw) {
		return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
