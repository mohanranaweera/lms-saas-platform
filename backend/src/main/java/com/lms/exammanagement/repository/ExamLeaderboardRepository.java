package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamLeaderboard;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped exam leaderboards (V115). */
public interface ExamLeaderboardRepository extends TenantAwareRepository<ExamLeaderboard, UUID> {

	default List<ExamLeaderboard> findAllOrdered() {
		return findAll((root, query, cb) -> cb.conjunction(), Sort.by("name").and(Sort.by("id")));
	}

	/** Published leaderboards among {@code ids} (student visibility). */
	default List<ExamLeaderboard> findPublishedByIdIn(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("id").in(ids), cb.isTrue(root.get("published"))),
				Sort.by("name").and(Sort.by("id")));
	}

}
