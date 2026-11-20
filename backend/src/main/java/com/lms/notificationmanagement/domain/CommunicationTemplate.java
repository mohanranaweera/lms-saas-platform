package com.lms.notificationmanagement.domain;

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
 * A reusable broadcast template (Wave 11, V67). Archived, never deleted.
 *
 * <p>Carries the WhatsApp pre-approval state machine (PAR-22-02):
 * {@code NOT_SUBMITTED -> SUBMITTED -> APPROVED | REJECTED}, {@code REJECTED
 * -> SUBMITTED}. Any edit to the copy of a submitted/approved template resets
 * it to {@code NOT_SUBMITTED} - an approval only ever covers the exact text
 * the provider reviewed.
 */
@Entity
@Table(name = "communication_template")
public class CommunicationTemplate extends Auditable implements TenantOwned {

	public enum Status {

		ACTIVE, ARCHIVED

	}

	public enum WhatsAppStatus {

		NOT_SUBMITTED, SUBMITTED, APPROVED, REJECTED

	}

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "subject", nullable = false, length = 200)
	private String subject;

	@Column(name = "body", nullable = false)
	private String body;

	@Column(name = "sms_body", length = 640)
	private String smsBody;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(name = "whatsapp_status", nullable = false, length = 15)
	private WhatsAppStatus whatsAppStatus;

	@Column(name = "whatsapp_template_name", length = 100)
	private String whatsAppTemplateName;

	@Column(name = "whatsapp_status_reason", length = 500)
	private String whatsAppStatusReason;

	@Column(name = "whatsapp_status_changed_at")
	private Instant whatsAppStatusChangedAt;

	@Column(name = "whatsapp_status_changed_by")
	private UUID whatsAppStatusChangedBy;

	protected CommunicationTemplate() {
	}

	public CommunicationTemplate(UUID tenantId, String name, String subject, String body, String smsBody) {
		this.tenantId = tenantId;
		this.name = name;
		this.subject = subject;
		this.body = body;
		this.smsBody = smsBody;
		this.status = Status.ACTIVE;
		this.whatsAppStatus = WhatsAppStatus.NOT_SUBMITTED;
	}

	/** @return true when the edit reset a WhatsApp submission/approval */
	public boolean updateContent(String name, String subject, String body, String smsBody) {
		requireActive();
		boolean copyChanged = !this.subject.equals(subject) || !this.body.equals(body)
				|| !java.util.Objects.equals(this.smsBody, smsBody);
		this.name = name;
		this.subject = subject;
		this.body = body;
		this.smsBody = smsBody;
		if (copyChanged && whatsAppStatus != WhatsAppStatus.NOT_SUBMITTED) {
			this.whatsAppStatus = WhatsAppStatus.NOT_SUBMITTED;
			this.whatsAppTemplateName = null;
			this.whatsAppStatusReason = "Template text changed; resubmit for approval";
			return true;
		}
		return false;
	}

	public void archive() {
		requireActive();
		this.status = Status.ARCHIVED;
	}

	public void submitForWhatsApp(String providerTemplateName, UUID actor, Instant at) {
		requireActive();
		if (whatsAppStatus != WhatsAppStatus.NOT_SUBMITTED && whatsAppStatus != WhatsAppStatus.REJECTED) {
			throw new IllegalStateException("Only a not-submitted or rejected template can be submitted");
		}
		transition(WhatsAppStatus.SUBMITTED, providerTemplateName, null, actor, at);
	}

	public void recordWhatsAppDecision(boolean approved, String reason, UUID actor, Instant at) {
		requireActive();
		if (whatsAppStatus != WhatsAppStatus.SUBMITTED) {
			throw new IllegalStateException("Only a submitted template can be approved or rejected");
		}
		transition(approved ? WhatsAppStatus.APPROVED : WhatsAppStatus.REJECTED, whatsAppTemplateName, reason, actor,
				at);
	}

	private void transition(WhatsAppStatus next, String providerTemplateName, String reason, UUID actor, Instant at) {
		this.whatsAppStatus = next;
		this.whatsAppTemplateName = providerTemplateName;
		this.whatsAppStatusReason = reason;
		this.whatsAppStatusChangedBy = actor;
		this.whatsAppStatusChangedAt = at;
	}

	private void requireActive() {
		if (status != Status.ACTIVE) {
			throw new IllegalStateException("Template is archived");
		}
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
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

	public String getSubject() {
		return subject;
	}

	public String getBody() {
		return body;
	}

	public String getSmsBody() {
		return smsBody;
	}

	public Status getStatus() {
		return status;
	}

	public WhatsAppStatus getWhatsAppStatus() {
		return whatsAppStatus;
	}

	public String getWhatsAppTemplateName() {
		return whatsAppTemplateName;
	}

	public String getWhatsAppStatusReason() {
		return whatsAppStatusReason;
	}

	public Instant getWhatsAppStatusChangedAt() {
		return whatsAppStatusChangedAt;
	}

}
