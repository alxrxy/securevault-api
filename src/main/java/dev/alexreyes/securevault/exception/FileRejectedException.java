package dev.alexreyes.securevault.exception;

import org.springframework.http.HttpStatus;

/** An upload that fails validation (empty, too large, or a disallowed type). */
public class FileRejectedException extends RuntimeException {

    private final HttpStatus status;

    public FileRejectedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
