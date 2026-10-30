package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/** SET-PAY-01: a student tried to submit a payment slip while the tenant does not accept bank transfer. */
public class BankTransferDisabledException extends ApplicationException {

	public BankTransferDisabledException(String message) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.BANK_TRANSFER_DISABLED, message);
	}

}
