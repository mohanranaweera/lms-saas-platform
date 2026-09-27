package com.lms.integrationmanagement.domain;

/**
 * Who switched a gateway account off. A {@code PLATFORM} switch-off can only
 * be undone by a Platform Admin (ADR-017 owner decision 4).
 */
public enum GatewayDisabledBy {

	TENANT, PLATFORM

}
