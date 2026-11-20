package com.lms.tutemanagement.domain;

/** Fulfillment state machine: PENDING to DISPATCHED (terminal, never undone) or to CANCELLED (terminal, releases stock). */
public enum FulfillmentStatus {

	PENDING, DISPATCHED, CANCELLED

}
