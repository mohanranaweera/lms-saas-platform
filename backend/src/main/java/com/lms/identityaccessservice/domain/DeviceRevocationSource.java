package com.lms.identityaccessservice.domain;

/** Who revoked a device ({@code device.revocation_source}, V64). */
public enum DeviceRevocationSource {

	/** A Tenant Admin reset (DEVICES/CREATE_EDIT). */
	ADMIN,

	/** The student revoked one of their own other devices (tenant self-service enabled). */
	STUDENT,

	/** Reserved for automated revocation (not used yet). */
	SYSTEM

}
