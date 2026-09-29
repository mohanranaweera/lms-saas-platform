package com.lms.integrationmanagement.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A media/meeting provider a tenant or teacher can connect (MEDIA-INT-01 /
 * ADR-018). ZOOM, YOUTUBE and VIMEO are per-teacher accounts connected via
 * OAuth; DYNTUBE and BUNNY are one tenant-wide account each, configured
 * with API keys by the Tenant Admin. Mirrors V60's
 * {@code ck_media_provider_connection_scope}.
 */
public enum MediaProvider {

	ZOOM(true), YOUTUBE(true), VIMEO(true), DYNTUBE(false), BUNNY(false);

	private final boolean teacherScoped;

	MediaProvider(boolean teacherScoped) {
		this.teacherScoped = teacherScoped;
	}

	/** True for per-teacher OAuth providers (Zoom/YouTube/Vimeo). */
	public boolean isTeacherScoped() {
		return teacherScoped;
	}

	/** Lowercase form used in URL paths ({@code zoom}, {@code bunny}, ...). */
	public String pathSegment() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Optional<MediaProvider> fromPathSegment(String segment) {
		if (segment == null) {
			return Optional.empty();
		}
		return Arrays.stream(values()).filter(p -> p.pathSegment().equals(segment)).findFirst();
	}

}
