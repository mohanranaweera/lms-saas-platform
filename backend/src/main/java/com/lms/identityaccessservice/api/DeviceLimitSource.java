package com.lms.identityaccessservice.api;

/** Which level of the device-limit precedence produced a resolved limit (most specific wins). */
public enum DeviceLimitSource {

	STUDENT, COURSE, TENANT, PLAN

}
