package com.example.library.domain.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
        super("email already exists: " + email);
    }
}