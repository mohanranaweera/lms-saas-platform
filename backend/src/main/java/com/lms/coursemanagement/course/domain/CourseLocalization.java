package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code course_localization} (V124, I18N-01 section 8): one public title/description variant of
 * a course for one locale. {@code title}/{@code description} are the working copy edited in the
 * course editor; {@code publishedTitle}/{@code publishedDescription} are what the storefront
 * serves while {@code status = PUBLISHED}. Drafts are never public.
 */
@Entity
@Table(name = "course_localization")
public class CourseLocalization extends Auditable implements TenantOwned {

	public enum Status {

		DRAFT, PUBLISHED

	}

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "locale", nullable = false, updatable = false, length = 16)
	private String locale;

	@Column(name = "title", nullable = false, length = 255)
	private String title;

	@Column(name = "description")
	private String description;

	@Column(name = "published_title", length = 255)
	private String publishedTitle;

	@Column(name = "published_description")
	private String publishedDescription;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private Status status = Status.DRAFT;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "published_by")
	private UUID publishedBy;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CourseLocalization() {
	}

	public CourseLocalization(UUID tenantId, UUID courseId, String locale) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.locale = locale;
	}

	public void edit(String title, String description) {
		this.title = title;
		this.description = description;
	}

	public void publish(UUID actorId, Instant at) {
		this.publishedTitle = title;
		this.publishedDescription = description;
		this.status = Status.PUBLISHED;
		this.publishedAt = at;
		this.publishedBy = actorId;
	}

	public void unpublish() {
		this.publishedTitle = null;
		this.publishedDescription = null;
		this.status = Status.DRAFT;
		this.publishedAt = null;
		this.publishedBy = null;
	}

	/** Working copy differs from what is published (always true for a never-published variant). */
	public boolean hasUnpublishedChanges() {
		return status != Status.PUBLISHED || !java.util.Objects.equals(title, publishedTitle)
				|| !java.util.Objects.equals(description, publishedDescription);
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

	public String getLocale() {
		return locale;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public String getPublishedTitle() {
		return publishedTitle;
	}

	public String getPublishedDescription() {
		return publishedDescription;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public UUID getPublishedBy() {
		return publishedBy;
	}

	public long getVersion() {
		return version;
	}

}
