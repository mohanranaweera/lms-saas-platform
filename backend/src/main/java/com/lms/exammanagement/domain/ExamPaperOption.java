package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * A frozen answer option of an {@link ExamPaperItem} ({@code exam_paper_option}, V112).
 * Its id is the stable option id students answer with (Slice C); {@code correct} is the
 * answer key and must never reach a student payload before release.
 */
@Entity
@Immutable
@Table(name = "exam_paper_option")
public class ExamPaperOption extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "paper_item_id", nullable = false, updatable = false)
	private UUID paperItemId;

	@Column(name = "sequence", nullable = false, updatable = false)
	private int sequence;

	@Column(name = "html", nullable = false, updatable = false)
	private String html;

	@Column(name = "correct", nullable = false, updatable = false)
	private boolean correct;

	protected ExamPaperOption() {
	}

	public ExamPaperOption(UUID tenantId, UUID paperItemId, int sequence, String html, boolean correct) {
		this.tenantId = tenantId;
		this.paperItemId = paperItemId;
		this.sequence = sequence;
		this.html = html;
		this.correct = correct;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getPaperItemId() {
		return paperItemId;
	}

	public int getSequence() {
		return sequence;
	}

	public String getHtml() {
		return html;
	}

	public boolean isCorrect() {
		return correct;
	}

}
