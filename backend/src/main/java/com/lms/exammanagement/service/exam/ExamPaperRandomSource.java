package com.lms.exammanagement.service.exam;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Supplies the randomness for paper draws. A bean (not a static) so tests can replace it
 * with a seeded generator for deterministic draws; the default is a {@link SecureRandom}
 * so a paper cannot be predicted from earlier draws.
 */
@Component
public class ExamPaperRandomSource {

	private final SecureRandom random = new SecureRandom();

	public RandomGenerator generator() {
		return random;
	}

}
