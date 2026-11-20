package com.lms.localizationmanagement.api;

/** Which step of the resolution chain (doc section 2) produced the locale. */
public enum LocaleSource {

	EXPLICIT, USER, COOKIE, BROWSER, TENANT_DEFAULT, PLATFORM

}
