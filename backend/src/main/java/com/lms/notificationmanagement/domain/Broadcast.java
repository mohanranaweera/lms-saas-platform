package com.lms.notificationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One sent broadcast (Wave 11, V67): the copy and the audience definition as
 * they were at send time. Append-only - every column is {@code updatable =
 * false} and a database trigger rejects UPDATE/DELETE.
 */
@Entity
@Table(name = "broadcast")
public class Broadcast extends BaseEntity implements TenantOwned {

	public enum AudienceType {

		ALL_STUDENTS, COURSE, SELECTED_STUDENTS, ALL_TEACHERS, PAYMENT_STATUS

	}

	public enum PaymentStatusFilter {

		PAID, PENDING, REJECTED

	}

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "client_request_id", nullable = false, updatable = false)
	private UUID clientRequestId;

	@Column(name = "sender_user_id", nullable = false, updatable = false)
	private UUID senderUserId;

	@Column(name = "sender_role", nullable = false, updatable = false, length = 30)
	private String senderRole;

	@Column(name = "template_id", updatable = false)
	private UUID templateId;

	@Column(name = "subject", nullable = false, updatable = false, length = 200)
	private String subject;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	@Column(name = "sms_body", updatable = false, length = 640)
	private String smsBody;

	@Column(name = "channel_in_app", nullable = false, updatable = false)
	private boolean channelInApp;

	@Column(name = "channel_email", nullable = false, updatable = false)
	private boolean channelEmail;

	@Column(name = "channel_sms", nullable = false, updatable = false)
	private boolean channelSms;

	@Column(name = "channel_whatsapp", nullable = false, updatable = false)
	private boolean channelWhatsApp;

	@Enumerated(EnumType.STRING)
	@Column(name = "audience_type", nullable = false, updatable = false, length = 20)
	private AudienceType audienceType;

	@Column(name = "audience_course_id", updatable = false)
	private UUID audienceCourseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "audience_payment_status", updatable = false, length = 10)
	private PaymentStatusFilter audiencePaymentStatus;

	@Column(name = "recipient_count", nullable = false, updatable = false)
	private int recipientCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Broadcast() {
	}

	public Broadcast(UUID tenantId, UUID clientRequestId, UUID senderUserId, String senderRole, UUID templateId,
			String subject, String body, String smsBody, Set<DeliveryChannel> channels, AudienceType audienceType,
			UUID audienceCourseId, PaymentStatusFilter audiencePaymentStatus, int recipientCount, Instant createdAt) {
		this.tenantId = tenantId;
		this.clientRequestId = clientRequestId;
		this.senderUserId = senderUserId;
		this.senderRole = senderRole;
		this.templateId = templateId;
		this.subject = subject;
		this.body = body;
		this.smsBody = smsBody;
		this.channelInApp = channels.contains(DeliveryChannel.IN_APP);
		this.channelEmail = channels.contains(DeliveryChannel.EMAIL);
		this.channelSms = channels.contains(DeliveryChannel.SMS);
		this.channelWhatsApp = channels.contains(DeliveryChannel.WHATSAPP);
		this.audienceType = audienceType;
		this.audienceCourseId = audienceCourseId;
		this.audiencePaymentStatus = audiencePaymentStatus;
		this.recipientCount = recipientCount;
		this.createdAt = createdAt;
	}

	public Set<DeliveryChannel> getChannels() {
		Set<DeliveryChannel> channels = EnumSet.noneOf(DeliveryChannel.class);
		if (channelInApp) {
			channels.add(DeliveryChannel.IN_APP);
		}
		if (channelEmail) {
			channels.add(DeliveryChannel.EMAIL);
		}
		if (channelSms) {
			channels.add(DeliveryChannel.SMS);
		}
		if (channelWhatsApp) {
			channels.add(DeliveryChannel.WHATSAPP);
		}
		return channels;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getClientRequestId() {
		return clientRequestId;
	}

	public UUID getSenderUserId() {
		return senderUserId;
	}

	public String getSenderRole() {
		return senderRole;
	}

	public UUID getTemplateId() {
		return templateId;
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

	public AudienceType getAudienceType() {
		return audienceType;
	}

	public UUID getAudienceCourseId() {
		return audienceCourseId;
	}

	public PaymentStatusFilter getAudiencePaymentStatus() {
		return audiencePaymentStatus;
	}

	public int getRecipientCount() {
		return recipientCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
