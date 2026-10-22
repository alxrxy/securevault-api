package dev.alexreyes.securevault.exception;

import java.time.Instant;

/**
 * Standard error body for every error response. Deliberately minimal -
 * never includes stack traces, database details, filesystem paths, or any
 * other internal detail. See GlobalExceptionHandler for what populates it.
 */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
