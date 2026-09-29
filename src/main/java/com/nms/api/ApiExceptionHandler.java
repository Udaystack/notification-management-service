package com.nms.api;

import com.nms.intake.SubmissionRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain rejections to RFC 9457 Problem Details. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(SubmissionRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(SubmissionRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("reason", e.reasonCode());
        if (!e.errors().isEmpty()) {
            problem.setProperty("errors", e.errors());
        }
        return ResponseEntity.status(e.status()).body(problem);
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotificationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }
}
