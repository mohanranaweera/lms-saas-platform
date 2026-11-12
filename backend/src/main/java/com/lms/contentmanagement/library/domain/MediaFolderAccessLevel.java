package com.lms.contentmanagement.library.domain;

/**
 * Level of an explicit per-folder grant ({@code media_folder_grant.access_level},
 * MEDIA-FOLDERS-01 section 4). {@code VIEW} = browse and open the folder's files; {@code
 * UPLOAD} = also add files (still needs Add Media). Neither gives management rights.
 */
public enum MediaFolderAccessLevel {

	VIEW, UPLOAD

}
