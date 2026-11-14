package com.example.travel.web;

import com.example.travel.model.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One JSON error shape: {"error": code, "message": text, ...extra}. Extends Spring's handler so framework errors
 * (bad query param type, unsupported media type, 405 ...) keep their proper status codes.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        body.putAll(e.extra());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<Object> lockTimeout(CannotAcquireLockException e) {
        log.warn("lock timeout", e);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of("error", "busy", "message", "the record is being modified by another request; retry"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> data(DataAccessException e) {
        log.error("database error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", "internal_error", "message", "unexpected database error"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", statusCode.value() == 400 ? "bad_request" : "http_" + statusCode.value());
        out.put("message", statusCode.value() == 400 ? "malformed or invalid request" : String.valueOf(ex.getMessage()));
        return ResponseEntity.status(statusCode).headers(headers).body(out);
    }
}
