package com.lms.exammanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.exammanagement.domain.ExamLeaderboardExam;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped leaderboard memberships (V115). */
public interface ExamLeaderboardExamRepository extends TenantAwareRepository<ExamLeaderboardExam, UUID> {

	default List<ExamLeaderboardExam> findAllByLeaderboardId(UUID leaderboardId) {
		return findAll((root, query, cb) -> cb.equal(root.get("leaderboardId"), leaderboardId));
	}

	default List<ExamLeaderboardExam> findAllByLeaderboardIdIn(Collection<UUID> leaderboardIds) {
		if (leaderboardIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("leaderboardId").in(leaderboardIds));
	}

	default List<ExamLeaderboardExam> findAllByExamIdIn(Collection<UUID> examIds) {
		if (examIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("examId").in(examIds));
	}

}
