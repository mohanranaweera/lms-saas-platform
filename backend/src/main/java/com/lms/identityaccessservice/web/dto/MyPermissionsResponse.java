package com.lms.identityaccessservice.web.dto;

import java.util.List;

/** {@code GET /api/v1/me/permissions}: the caller's live role, held capability keys and matrix version. */
public record MyPermissionsResponse(String role, List<String> capabilities, long permissionVersion) {

}
