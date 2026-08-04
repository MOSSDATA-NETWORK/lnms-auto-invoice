package com.autoinvoice.api.problem;

import com.autoinvoice.api.http.RequestIdFilter;
import com.autoinvoice.platform.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.status()), exception.getMessage());
        problem.setType(URI.create("https://auto-invoice.example/problems/" + kebab(exception.code())));
        problem.setTitle(humanize(exception.code()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", exception.code());
        problem.setProperty("request_id", request.getHeader(RequestIdFilter.HEADER));
        exception.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(URI.create("https://auto-invoice.example/problems/validation-failed"));
        problem.setTitle("Validation failed");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("request_id", request.getHeader(RequestIdFilter.HEADER));
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", exception.getStatusCode().value() == 401
                ? "AUTHENTICATION_REQUIRED" : "REQUEST_REJECTED");
        problem.setProperty("request_id", request.getHeader(RequestIdFilter.HEADER));
        return problem;
    }

    private Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message", error.getDefaultMessage());
    }

    private String kebab(String code) {
        return code.toLowerCase().replace('_', '-');
    }

    private String humanize(String code) {
        String lower = code.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
