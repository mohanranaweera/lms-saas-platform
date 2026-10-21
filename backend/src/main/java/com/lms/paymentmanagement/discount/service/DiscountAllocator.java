package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D4 allocation: a discount total (scale 2) is spread over the eligible lines
 * pro rata by unit amount using the largest-remainder method in whole cents -
 * every share is floored, then the leftover cents go one each to the lines with
 * the largest fractional remainder (ties: lower {@code lineNo}). The shares sum
 * exactly to the total and no share exceeds its line's unit amount (the total
 * is never more than the lines' sum).
 */
public final class DiscountAllocator {

	private DiscountAllocator() {
	}

	/**
	 * @return lineNo -> allocated amount (scale 2), only for lines receiving
	 * more than zero, in lineNo order.
	 * @throws IllegalArgumentException if the total is negative or exceeds the lines' sum.
	 */
	public static Map<Integer, BigDecimal> allocate(BigDecimal total, List<CartLine> lines) {
		long totalCents = cents(total);
		List<CartLine> sorted = new ArrayList<>(lines);
		sorted.sort(Comparator.comparingInt(CartLine::lineNo));
		BigInteger sum = BigInteger.ZERO;
		for (CartLine line : sorted) {
			sum = sum.add(BigInteger.valueOf(cents(line.unitAmount())));
		}
		if (totalCents < 0 || BigInteger.valueOf(totalCents).compareTo(sum) > 0) {
			throw new IllegalArgumentException("Discount total must be between 0 and the eligible lines' total");
		}
		Map<Integer, BigDecimal> result = new LinkedHashMap<>();
		if (totalCents == 0 || sum.signum() == 0) {
			return result;
		}
		int n = sorted.size();
		long[] shares = new long[n];
		BigInteger[] remainders = new BigInteger[n];
		long allocated = 0;
		BigInteger bigTotal = BigInteger.valueOf(totalCents);
		for (int i = 0; i < n; i++) {
			BigInteger[] division = bigTotal.multiply(BigInteger.valueOf(cents(sorted.get(i).unitAmount())))
				.divideAndRemainder(sum);
			shares[i] = division[0].longValueExact();
			remainders[i] = division[1];
			allocated += shares[i];
		}
		long leftover = totalCents - allocated;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			order.add(i);
		}
		order.sort((a, b) -> {
			int byRemainder = remainders[b].compareTo(remainders[a]);
			return byRemainder != 0 ? byRemainder
					: Integer.compare(sorted.get(a).lineNo(), sorted.get(b).lineNo());
		});
		for (int k = 0; k < leftover; k++) {
			shares[order.get(k)]++;
		}
		for (int i = 0; i < n; i++) {
			if (shares[i] > 0) {
				result.put(sorted.get(i).lineNo(), BigDecimal.valueOf(shares[i], 2));
			}
		}
		return result;
	}

	private static long cents(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
	}

}
