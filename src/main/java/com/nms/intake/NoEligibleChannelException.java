package com.nms.intake;

/** No recipient got any delivery; the intake transaction must roll back. */
public class NoEligibleChannelException extends RuntimeException {

    public NoEligibleChannelException() {
        super("No recipient has an eligible channel");
    }
}
