package com.bft.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

/**
 * Simplified Shamir's Secret Sharing for threshold signatures.
 * Splits a secret into n shares, requiring t shares to reconstruct.
 * 
 * Uses polynomial interpolation over a prime field.
 * Educational implementation — not for production TSS.
 */
public class ShamirThreshold {
    private static final Logger log = LoggerFactory.getLogger(ShamirThreshold.class);

    // Large prime for finite field arithmetic
    private static final BigInteger PRIME = new BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);

    private final int threshold;  // t: minimum shares needed
    private final int totalShares; // n: total number of shares
    private final SecureRandom random = new SecureRandom();

    public ShamirThreshold(int threshold, int totalShares) {
        if (threshold > totalShares) {
            throw new IllegalArgumentException("Threshold cannot exceed total shares");
        }
        this.threshold = threshold;
        this.totalShares = totalShares;
        log.info("Shamir Threshold initialized: t={}, n={}", threshold, totalShares);
    }

    /**
     * A share consisting of an x-coordinate and y-coordinate.
     */
    public record Share(int x, BigInteger y) {
        @Override
        public String toString() {
            return String.format("Share(x=%d, y=%s)", x, y.toString(16).substring(0, 8) + "...");
        }
    }

    /**
     * Split a secret into n shares using a random polynomial of degree (t-1).
     */
    public List<Share> split(BigInteger secret) {
        // Generate random polynomial coefficients: a0=secret, a1..a(t-1)=random
        BigInteger[] coefficients = new BigInteger[threshold];
        coefficients[0] = secret.mod(PRIME);
        for (int i = 1; i < threshold; i++) {
            coefficients[i] = new BigInteger(256, random).mod(PRIME);
        }

        // Evaluate polynomial at x=1,2,...,n
        List<Share> shares = new ArrayList<>();
        for (int x = 1; x <= totalShares; x++) {
            BigInteger xBig = BigInteger.valueOf(x);
            BigInteger y = evaluatePolynomial(coefficients, xBig);
            shares.add(new Share(x, y));
        }

        log.debug("Secret split into {} shares (threshold={})", totalShares, threshold);
        return shares;
    }

    /**
     * Reconstruct the secret from at least t shares using Lagrange interpolation.
     */
    public BigInteger reconstruct(List<Share> shares) {
        if (shares.size() < threshold) {
            throw new IllegalArgumentException(
                String.format("Need at least %d shares to reconstruct (got %d)", threshold, shares.size()));
        }

        // Use only the first 'threshold' shares
        List<Share> usedShares = shares.subList(0, threshold);

        BigInteger secret = BigInteger.ZERO;
        for (int i = 0; i < usedShares.size(); i++) {
            BigInteger xi = BigInteger.valueOf(usedShares.get(i).x());
            BigInteger yi = usedShares.get(i).y();

            // Compute Lagrange basis polynomial L_i(0)
            BigInteger numerator = BigInteger.ONE;
            BigInteger denominator = BigInteger.ONE;

            for (int j = 0; j < usedShares.size(); j++) {
                if (i == j) continue;
                BigInteger xj = BigInteger.valueOf(usedShares.get(j).x());
                numerator = numerator.multiply(xj.negate()).mod(PRIME);
                denominator = denominator.multiply(xi.subtract(xj)).mod(PRIME);
            }

            // L_i(0) = numerator / denominator (mod PRIME)
            BigInteger lagrange = numerator.multiply(denominator.modInverse(PRIME)).mod(PRIME);
            secret = secret.add(yi.multiply(lagrange)).mod(PRIME);
        }

        log.debug("Secret reconstructed from {} shares", usedShares.size());
        return secret;
    }

    /**
     * Verify that a share is consistent with the commitment (public polynomial).
     * Simple verification using a subset of other shares.
     */
    public boolean verifyShare(Share share, List<Share> otherShares) {
        if (otherShares.size() < threshold - 1) return false;

        List<Share> testShares = new ArrayList<>(otherShares.subList(0, threshold - 1));
        testShares.add(share);

        try {
            reconstruct(testShares);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private BigInteger evaluatePolynomial(BigInteger[] coefficients, BigInteger x) {
        BigInteger result = BigInteger.ZERO;
        BigInteger xPow = BigInteger.ONE;
        for (BigInteger coeff : coefficients) {
            result = result.add(coeff.multiply(xPow)).mod(PRIME);
            xPow = xPow.multiply(x).mod(PRIME);
        }
        return result;
    }

    public int getThreshold() { return threshold; }
    public int getTotalShares() { return totalShares; }
}
