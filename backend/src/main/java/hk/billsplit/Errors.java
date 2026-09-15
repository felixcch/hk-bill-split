package hk.billsplit;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
  private static final Logger LOG = LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, String>> domain(ApiException e) {
    return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<Map<String, String>> validation(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Map<String, String>> constraint(DataIntegrityViolationException e) {
    LOG.warn("Database constraint rejected a write");
    return ResponseEntity.status(409).body(Map.of("code", "CONFLICT"));
  }
}
