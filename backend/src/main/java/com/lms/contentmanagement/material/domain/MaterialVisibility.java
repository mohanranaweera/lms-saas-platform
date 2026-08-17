package com.lms.contentmanagement.material.domain;

/**
 * {@code material.visibility} (V16) - the issue's own accepted MVP default:
 * a minimal CHECK-constrained VISIBLE/HIDDEN taxonomy, not a richer one. See
 * {@code docs/plans/MVP-009 Lessons and Learning Materials.md} §21 item 4 -
 * no richer taxonomy is defined anywhere in the requirements corpus;
 * widening this later is a new migration, not an edit to this enum in
 * place.
 */
public enum MaterialVisibility {

	VISIBLE, HIDDEN

}
