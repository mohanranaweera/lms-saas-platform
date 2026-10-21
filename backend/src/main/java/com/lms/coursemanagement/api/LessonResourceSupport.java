package com.lms.coursemanagement.api;

/**
 * How far the platform actually supports a {@link LessonResourceType}
 * (KLASS-T3, {@code GET /api/v1/courses/lesson-resource-types}). This is a
 * statement about the code that exists. It is not a promise that a given
 * tenant has configured the provider.
 */
public enum LessonResourceSupport {

	/**
	 * Backed by a real platform integration: content is uploaded, created or
	 * played through our own services (secure storage, the secure video
	 * pipeline, or the live-class provider adapter). The provider may still need
	 * tenant/teacher setup, and the help text says what that setup is.
	 */
	INTEGRATED,

	/**
	 * No provider integration. The session holds an external URL ({@code
	 * material} of type {@code LINK}) that opens on the provider's own site. We
	 * never create, verify, protect or track the external resource.
	 */
	LINK_ONLY,

	/**
	 * Not available yet. The type exists so the session can be labelled the way
	 * Klass labels it, but no working content path exists. The UI must show it
	 * as unavailable and never as a success state.
	 */
	UNSUPPORTED

}
