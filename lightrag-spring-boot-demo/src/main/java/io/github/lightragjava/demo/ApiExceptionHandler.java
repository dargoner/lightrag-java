package io.github.lightragjava.demo;

import jakarta.servlet.ServletException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleIllegalArgument(IllegalArgumentException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleNotFound(NoSuchElementException exception) {
        return new ErrorResponse(exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        var message = exception.getReason() == null ? exception.getMessage() : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(message));
    }

    @ExceptionHandler(ServletException.class)
    ResponseEntity<ErrorResponse> handleServletException(ServletException exception) {
        if (exception.getCause() instanceof IllegalArgumentException illegalArgumentException) {
            return ResponseEntity.badRequest().body(new ErrorResponse(illegalArgumentException.getMessage()));
        }
        if (exception.getCause() instanceof ResponseStatusException responseStatusException) {
            return handleResponseStatus(responseStatusException);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(exception.getMessage() == null ? "unexpected server error" : exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ErrorResponse handleUnexpected(Exception exception) {
        return new ErrorResponse(exception.getMessage() == null ? "unexpected server error" : exception.getMessage());
    }

    record ErrorResponse(String error, String message) {
        ErrorResponse(String message) {
            this(message, message);
        }
    }
}
