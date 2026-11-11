package com.coinspay.mc.core;

/** A failed API call: an HTTP error with the CoinsPay error code, or a network failure (status 0). */
public final class ApiException extends Exception {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "NETWORK";
    }

    /** Worth retrying with the same Idempotency-Key: the network, 5xx or a rate limit. */
    public boolean retryable() {
        return status == 0 || status >= 500 || status == 429;
    }

    public boolean rateLimited() {
        return status == 429;
    }

    @Override
    public String toString() {
        return status == 0 ? "network error: " + getMessage() : "HTTP " + status + " " + code + ": " + getMessage();
    }
}
