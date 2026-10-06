package com.haoyu.inbound.common;

/** The request contradicts the current state (answered with 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
