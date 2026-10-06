package com.haoyu.inbound.orders;

import com.haoyu.inbound.common.ConflictException;

/** The order is in a state that does not allow the requested transition (e.g. shipping a backorder). */
public class OrderConflictException extends ConflictException {

    public OrderConflictException(String message) {
        super(message);
    }
}
