package com.lms.common.error;

import org.springframework.http.HttpStatus;

/**
 * VIDEO Phase 8: a refused AOC Player request (pairing, exchange, heartbeat).
 * Messages are deliberately generic - the precise reason goes to the security
 * log only (VID-ADR-008), so the response is never an oracle for probing codes,
 * installations or signatures.
 */
public class PlayerRequestException extends ApplicationException {

	public PlayerRequestException(HttpStatus httpStatus, String errorCode, String message) {
		super(httpStatus, errorCode, message);
	}

}
