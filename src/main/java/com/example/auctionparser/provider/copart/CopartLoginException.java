package com.example.auctionparser.provider.copart;

/** Raised when the Copart headless-browser login cannot be completed. */
public class CopartLoginException extends RuntimeException {

    public CopartLoginException(String message) {
        super(message);
    }

    public CopartLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
