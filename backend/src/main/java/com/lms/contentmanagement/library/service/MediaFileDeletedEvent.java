package com.lms.contentmanagement.library.service;

import java.util.UUID;

/**
 * Internal to the library: published inside the soft-delete transaction so the
 * stored object is removed only AFTER that transaction commits (never on
 * rollback, never while a DB transaction is open across the storage call).
 */
public record MediaFileDeletedEvent(UUID tenantId, UUID mediaFileId, String objectKey) {

}
