package com.lms.videoaccessmanagement.domain;

/**
 * What the moving watermark may show (plan §11/§15). The text itself is
 * built server-side per session (Phase 11); only masked personal data, never
 * a full email or phone number.
 */
public enum WatermarkField {

	STUDENT_DISPLAY_ID, MASKED_EMAIL, MASKED_PHONE, SESSION_ID, TIMESTAMP

}
