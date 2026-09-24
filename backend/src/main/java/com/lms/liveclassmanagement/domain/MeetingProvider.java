package com.lms.liveclassmanagement.domain;

/**
 * {@code class_session.meeting_provider} (V49's {@code
 * ck_class_session_meeting_provider} CHECK constraint). Only {@link #ZOOM}
 * exists this wave (Wave 4 plan §11: multi-provider support is out of
 * scope) - modeled as an enum anyway (rather than a bare constant) so a
 * future second provider is a schema-compatible enum addition, not a
 * column-type change.
 */
public enum MeetingProvider {

	ZOOM

}
