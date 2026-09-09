package com.lms.notificationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code notification_template} (V28). Extends {@link
 * Auditable} - this table carries the standard {@code created_at}/{@code
 * updated_at}/{@code created_by}/{@code updated_by} columns even though no
 * authoring endpoint exists yet in this MVP (V28's header comment; see also
 * {@code NotificationTemplateSeedingService}'s javadoc for why these rows
 * currently only ever get seeded, never authored, at MVP launch).
 */
@Entity
@Table(name = "notification_template")
public class NotificationTemplate extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "template_key", nullable = false, length = 60)
	private String templateKey;

	@Column(name = "subject", nullable = false)
	private String subject;

	@Column(name = "body", nullable = false)
	private String body;

	protected NotificationTemplate() {
	}

	public NotificationTemplate(UUID tenantId, String templateKey, String subject, String body) {
		this.tenantId = tenantId;
		this.templateKey = templateKey;
		this.subject = subject;
		this.body = body;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getTemplateKey() {
		return templateKey;
	}

	public String getSubject() {
		return subject;
	}

	public String getBody() {
		return body;
	}

}
