package com.lms.identityaccessservice.domain;

/** {@code device.status} (V64). A revoked device is never reactivated - the next login from it registers anew. */
public enum DeviceStatus {

	ACTIVE, REVOKED

}
