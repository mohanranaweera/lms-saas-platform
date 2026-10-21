package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.integrationmanagement.api.MediaProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The tenant's integration switches (MEDIA-INT-01, V60): which teacher-level
 * providers teachers may connect, and which tenant video host is active. At
 * most one row per tenant; no row means the defaults (everything enabled, no
 * video host).
 */
@Entity
@Table(name = "tenant_media_integration_settings")
public class TenantMediaIntegrationSettings extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "zoom_enabled", nullable = false)
	private boolean zoomEnabled = true;

	@Column(name = "youtube_enabled", nullable = false)
	private boolean youtubeEnabled = true;

	@Column(name = "vimeo_enabled", nullable = false)
	private boolean vimeoEnabled = true;

	@Enumerated(EnumType.STRING)
	@Column(name = "video_host", nullable = false, length = 10)
	private VideoHost videoHost = VideoHost.NONE;

	protected TenantMediaIntegrationSettings() {
	}

	public TenantMediaIntegrationSettings(UUID tenantId) {
		this.tenantId = tenantId;
	}

	/** Whether teachers may connect/use this teacher-level provider. Tenant-level providers are always allowed. */
	public boolean isEnabled(MediaProvider provider) {
		return switch (provider) {
			case ZOOM -> zoomEnabled;
			case YOUTUBE -> youtubeEnabled;
			case VIMEO -> vimeoEnabled;
			case DYNTUBE, BUNNY -> true;
		};
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public boolean isZoomEnabled() {
		return zoomEnabled;
	}

	public boolean isYoutubeEnabled() {
		return youtubeEnabled;
	}

	public boolean isVimeoEnabled() {
		return vimeoEnabled;
	}

	public void setTeacherProviders(boolean zoom, boolean youtube, boolean vimeo) {
		this.zoomEnabled = zoom;
		this.youtubeEnabled = youtube;
		this.vimeoEnabled = vimeo;
	}

	public VideoHost getVideoHost() {
		return videoHost;
	}

	public void setVideoHost(VideoHost videoHost) {
		this.videoHost = videoHost;
	}

}
