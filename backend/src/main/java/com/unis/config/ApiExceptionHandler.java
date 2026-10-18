package com.unis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Database errors that escape a controller used to reach the user word for
 * word (server.error.include-message is "always"), which hands out table
 * names, column names and SQL. They're now logged in full on the server and
 * answered with a plain message.
 *
 * Only database errors are handled here, on purpose: the many friendly
 * messages the app throws itself ("Title is required", ...) still reach the
 * user exactly as before.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        log.warn("[Api] data conflict: {}", e.getMostSpecificCause().getMessage());
        String msg = "That couldn't be saved because it conflicts with something that already exists.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", msg, "message", msg));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> database(DataAccessException e) {
        log.error("[Api] database error", e);
        String msg = "Something went wrong on our side. Please try again.";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", msg, "message", msg));
    }
}
