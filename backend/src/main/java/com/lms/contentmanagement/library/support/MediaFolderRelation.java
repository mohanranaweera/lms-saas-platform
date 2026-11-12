package com.lms.contentmanagement.library.support;

/**
 * The caller's relation to one folder (MEDIA-FOLDERS-01 section 4), strongest first: {@code
 * OWNER} (owner_user_id = caller), {@code TENANT} (tenant-wide folder authority), {@code UPLOAD}
 * / {@code VIEW} (explicit grant). No relation = the folder is invisible (404).
 */
public enum MediaFolderRelation {

	OWNER, TENANT, UPLOAD, VIEW

}
