package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

/**
 * One successful, atomic CSV import into a module question bank ({@code question_import},
 * V110). Immutable after insert. {@code courseId}/{@code moduleId} are opaque
 * course-management ids (schema-enforced by a composite FK, never a JPA association).
 */
@Entity
@Table(name = "question_import")
public class QuestionImport extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "module_id", nullable = false, updatable = false)
	private UUID moduleId;

	@Column(name = "file_name", nullable = false, updatable = false)
	private String fileName;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "file_sha256", nullable = false, updatable = false, length = 64)
	private String fileSha256;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Column(name = "question_count", nullable = false, updatable = false)
	private int questionCount;

	@Column(name = "imported_by", nullable = false, updatable = false)
	private UUID importedBy;

	@Column(name = "imported_at", nullable = false, updatable = false)
	private Instant importedAt;

	protected QuestionImport() {
	}

	public QuestionImport(UUID tenantId, UUID courseId, UUID moduleId, String fileName, String fileSha256,
			String idempotencyKey, int questionCount, UUID importedBy, Instant importedAt) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.moduleId = moduleId;
		this.fileName = fileName;
		this.fileSha256 = fileSha256;
		this.idempotencyKey = idempotencyKey;
		this.questionCount = questionCount;
		this.importedBy = importedBy;
		this.importedAt = importedAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getModuleId() {
		return moduleId;
	}

	public String getFileName() {
		return fileName;
	}

	public String getFileSha256() {
		return fileSha256;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public int getQuestionCount() {
		return questionCount;
	}

	public UUID getImportedBy() {
		return importedBy;
	}

	public Instant getImportedAt() {
		return importedAt;
	}

}
