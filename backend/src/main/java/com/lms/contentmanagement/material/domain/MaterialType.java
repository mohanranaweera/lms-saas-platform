package com.lms.contentmanagement.material.domain;

/**
 * {@code material.material_type} (V50/Wave 5, PAR-06-03/PAR-06-05/PAR-27-01) -
 * the discriminator that relaxes {@code Material} from a single-uploaded
 * -file shape into one of several sub-types with different required-field
 * rules, enforced both by V51's {@code ck_material_*_required} CHECK
 * constraints and (for a clean 400 instead of a raw constraint-violation
 * 500) by {@code MaterialService#createMaterial}'s own validation:
 *
 * <ul>
 * <li>{@link #PDF}/{@link #IMAGE}/{@link #DOCUMENT}/{@link #OTHER} - an
 * uploaded file; require {@code storageObjectKey}.</li>
 * <li>{@link #LINK} - an external URL (e.g. YouTube/Vimeo); requires {@code
 * externalUrl}, never a file.</li>
 * <li>{@link #NOTE} - inline text; requires {@code noteContent}, never a
 * file.</li>
 * <li>{@link #VIDEO}/{@link #RECORDING} - a self-uploaded secure video asset
 * managed by {@code video-access-management}; require {@code
 * videoAssetId}. Purely semantic labeling between the two (original
 * instructional video vs. a recorded class session made available
 * afterward) - both share the identical {@code VideoAsset}/{@code
 * VideoPlaybackPolicy} security mechanism (plan §10 item 3).</li>
 * </ul>
 */
public enum MaterialType {

	PDF, IMAGE, DOCUMENT, LINK, NOTE, VIDEO, RECORDING, OTHER

}
