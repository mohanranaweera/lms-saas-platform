package com.lms.identityaccessservice.service.permission;

import java.util.List;
import java.util.Map;

/**
 * STAFF-MGMT-02 §2.5 response of {@code GET /api/v1/staff/permission-matrix}. Cell state is
 * one of {@code GRANTED}, {@code NOT_GRANTED}, {@code LOCKED}, {@code UNAVAILABLE}; only
 * GRANTED / NOT_GRANTED cells are editable.
 */
public record PermissionMatrixView(long version, List<RoleColumn> roles, List<Group> groups) {

	public record RoleColumn(String code, String displayName) {

	}

	public record Group(String key, String label, List<Row> rows) {

	}

	public record Row(String key, String label, String availability, String reason, Map<String, Cell> cells) {

	}

	public record Cell(String state, String reason) {

	}

}
