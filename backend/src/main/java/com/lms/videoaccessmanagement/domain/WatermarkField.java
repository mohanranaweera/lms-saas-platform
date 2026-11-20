package com.lms.videoaccessmanagement.domain;

/**
 * What the moving watermark may show (plan §11/§15). The text itself is
 * built server-side per session (Phase 11); full names are resolved within the
 * tenant, while email and phone values remain masked.
 */
public enum WatermarkField {

	STUDENT_DISPLAY_ID, STUDENT_FULL_NAME, MASKED_EMAIL, MASKED_PHONE, SESSION_ID, TIMESTAMP

}
