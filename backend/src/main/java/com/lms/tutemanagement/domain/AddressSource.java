package com.lms.tutemanagement.domain;

/** Where a delivery address was resolved from (spec section 6): the order's delivery snapshot, the student profile, or nowhere (missing). */
public enum AddressSource {

	ORDER, PROFILE, NONE

}
