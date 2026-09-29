package com.nms.intake;

import java.util.List;
import org.springframework.http.HttpStatus;

/** A submission that was refused; the rejection has already been audited when this is thrown. */
public class SubmissionRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final String reasonCode;
    private final List<FieldError> errors;

    public SubmissionRejectedException(HttpStatus status, String reasonCode, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.reasonCode = reasonCode;
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
