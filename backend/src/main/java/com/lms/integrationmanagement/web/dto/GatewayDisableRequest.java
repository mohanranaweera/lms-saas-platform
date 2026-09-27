package com.lms.integrationmanagement.web.dto;

import jakarta.validation.constraints.Size;

/** Reason for switching online payments off (required for Platform Admin, optional for Tenant Admin). */
public record GatewayDisableRequest(@Size(max = 500) String reason) {

}
