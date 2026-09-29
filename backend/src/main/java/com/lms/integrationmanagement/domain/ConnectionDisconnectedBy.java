package com.lms.integrationmanagement.domain;

/**
 * Who switched a media provider connection off (V60): the owning teacher, a
 * Tenant Admin, the provider itself (e.g. Zoom app deauthorized), or the
 * platform replacing it with a newer connection.
 */
public enum ConnectionDisconnectedBy {

	OWNER, TENANT_ADMIN, PROVIDER, SYSTEM

}
