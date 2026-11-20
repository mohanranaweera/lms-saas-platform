package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * {@code exam_leaderboard} (V115, D7): a named grouping of a tenant's Klass exams. {@code
 * published} makes it visible to students enrolled in one of its exams. Deleting it removes the
 * grouping only.
 */
@Entity
@Table(name = "exam_leaderboard")
public class ExamLeaderboard extends Auditable implements TenantOwned {

	public static final int MAX_NAME_LENGTH = 120;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Column(name = "published", nullable = false)
	private boolean published;

	protected ExamLeaderboard() {
	}

	public ExamLeaderboard(UUID tenantId, String name, boolean published) {
		this.tenantId = tenantId;
		this.name = name;
		this.published = published;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public boolean isPublished() {
		return published;
	}

	public void update(String name, boolean published) {
		this.name = name;
		this.published = published;
	}

}
