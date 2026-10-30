package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** {@code exam_leaderboard_exam} (V115): one exam of a leaderboard (composite same-tenant FKs). */
@Entity
@Immutable
@Table(name = "exam_leaderboard_exam")
public class ExamLeaderboardExam extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "leaderboard_id", nullable = false, updatable = false)
	private UUID leaderboardId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	protected ExamLeaderboardExam() {
	}

	public ExamLeaderboardExam(UUID tenantId, UUID leaderboardId, UUID examId) {
		this.tenantId = tenantId;
		this.leaderboardId = leaderboardId;
		this.examId = examId;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getLeaderboardId() {
		return leaderboardId;
	}

	public UUID getExamId() {
		return examId;
	}

}
