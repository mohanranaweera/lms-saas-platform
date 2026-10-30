package com.lms.integrationmanagement.api;

/**
 * AES-256-GCM encryption of exam passwords at rest (Klass Exam Module D6). The key is the
 * dedicated {@code exam.password-encryption-key} ({@code EXAM_PASSWORD_ENCRYPTION_KEY});
 * it is never shared with the payment or media credential keys. {@code context} is bound
 * to the ciphertext as additional authenticated data, so a value copied to another exam
 * cannot be decrypted there.
 *
 * <p>Fails closed: without a usable key every call throws a 503 - an exam password is
 * never stored in plaintext or with a weak key. Plaintext values are never logged.
 */
public interface ExamPasswordCipherApi {

	String encrypt(String plaintext, String context);

	String decrypt(String ciphertext, String context);

}
