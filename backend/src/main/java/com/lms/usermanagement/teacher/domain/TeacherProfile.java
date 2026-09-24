package com.lms.usermanagement.teacher.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The operational/approval aspect of a teacher account, owned by
 * {@code user-management} (MVP-007 Teacher Management), mapped 1:1 onto
 * {@code teacher_profile} (V18). The credential aspect (email, password
 * hash, role, login-gate status) lives on {@code identity-access-service}'s
 * {@code tenant_user} row instead - {@code userId} below is an opaque
 * foreign key value only, never a JPA {@code @ManyToOne} across the module
 * boundary (per {@code .claude/rules/architecture.md}: a module must never
 * import another domain's {@code domain} classes). Any read/mutation of the
 * credential row goes through {@code identity-access-service}'s {@code api}
 * package. Mirrors {@link com.lms.usermanagement.staff.domain.StaffProfile}'s
 * shape exactly, plus the approval-workflow fields Staff has no equivalent
 * of.
 */
@Entity
@Table(name = "teacher_profile")
public class TeacherProfile extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "name", nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "approval_status", nullable = false)
	private ApprovalStatus approvalStatus;

	@Column(name = "approved_by")
	private UUID approvedBy;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "suspended_by")
	private UUID suspendedBy;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "reactivated_by")
	private UUID reactivatedBy;

	@Column(name = "reactivated_at")
	private Instant reactivatedAt;

	protected TeacherProfile() {
	}

	public TeacherProfile(UUID tenantId, UUID userId, String name) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.name = name;
		this.approvalStatus = ApprovalStatus.PENDING;
		this.approvedBy = null;
		this.approvedAt = null;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public ApprovalStatus getApprovalStatus() {
		return approvalStatus;
	}

	public UUID getApprovedBy() {
		return approvedBy;
	}

	public Instant getApprovedAt() {
		return approvedAt;
	}

	public UUID getSuspendedBy() {
		return suspendedBy;
	}

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public UUID getReactivatedBy() {
		return reactivatedBy;
	}

	public Instant getReactivatedAt() {
		return reactivatedAt;
	}

	/**
	 * Transitions {@code PENDING -> APPROVED}, recording the deciding Tenant
	 * Admin's {@code tenant_user.id} and the decision time. Keeps the
	 * one-directional-transition invariant close to the entity rather than
	 * scattered in the service layer - {@link TeacherProfile}'s own state is
	 * the single source of truth for whether a transition is legal.
	 *
	 * @throws IllegalStateException if {@code approvalStatus} is not
	 * currently {@code PENDING} - translated by {@code TeacherService} into
	 * the public {@code 409 InvalidApprovalStateException}.
	 */
	public void approve(UUID approverId, Instant when) {
		requirePending();
		this.approvalStatus = ApprovalStatus.APPROVED;
		this.approvedBy = approverId;
		this.approvedAt = when;
	}

	/**
	 * Transitions {@code PENDING -> REJECTED}, recording the deciding Tenant
	 * Admin's {@code tenant_user.id} and the decision time - {@code
	 * approved_by}/{@code approved_at} are populated on both outcomes (§7 of
	 * the approved MVP-007 plan), not only on approval.
	 *
	 * @throws IllegalStateException if {@code approvalStatus} is not
	 * currently {@code PENDING}.
	 */
	public void reject(UUID reviewerId, Instant when) {
		requirePending();
		this.approvalStatus = ApprovalStatus.REJECTED;
		this.approvedBy = reviewerId;
		this.approvedAt = when;
	}

	private void requirePending() {
		if (this.approvalStatus != ApprovalStatus.PENDING) {
			throw new IllegalStateException(
					"Cannot transition a teacher approval that is not currently PENDING (current status: "
							+ this.approvalStatus + ")");
		}
	}

	/**
	 * Transitions {@code APPROVED -> SUSPENDED} (Wave 3) - a second,
	 * independent lifecycle pair from {@link #approve}/{@link #reject},
	 * never reachable from {@code PENDING}/{@code REJECTED}.
	 * @throws IllegalStateException if {@code approvalStatus} is not
	 * currently {@code APPROVED}.
	 */
	public void suspend(UUID actorId, Instant when) {
		if (this.approvalStatus != ApprovalStatus.APPROVED) {
			throw new IllegalStateException(
					"Cannot suspend a teacher that is not currently APPROVED (current status: " + this.approvalStatus
							+ ")");
		}
		this.approvalStatus = ApprovalStatus.SUSPENDED;
		this.suspendedBy = actorId;
		this.suspendedAt = when;
	}

	/**
	 * Transitions {@code SUSPENDED -> APPROVED} (Wave 3) - the only legal
	 * reverse of {@link #suspend}.
	 * @throws IllegalStateException if {@code approvalStatus} is not
	 * currently {@code SUSPENDED}.
	 */
	public void reactivate(UUID actorId, Instant when) {
		if (this.approvalStatus != ApprovalStatus.SUSPENDED) {
			throw new IllegalStateException(
					"Cannot reactivate a teacher that is not currently SUSPENDED (current status: "
							+ this.approvalStatus + ")");
		}
		this.approvalStatus = ApprovalStatus.APPROVED;
		this.reactivatedBy = actorId;
		this.reactivatedAt = when;
	}

}
