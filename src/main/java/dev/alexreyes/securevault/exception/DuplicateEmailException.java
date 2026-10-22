package dev.alexreyes.securevault.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String message) {
        super(message);
    }
}
