package com.fourkites.event.scheduler.service;

import java.sql.SQLException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Service for handling database errors during batch processing. Provides categorization of DB
 * errors and retry strategies.
 */
@Service
public class DatabaseErrorHandlingService {

  private static final Logger log = LoggerFactory.getLogger(DatabaseErrorHandlingService.class);

  private final int maxRetries;
  private final long retryBaseDelayMs;

  public DatabaseErrorHandlingService(com.fourkites.event.scheduler.config.AppProperties appProps) {
    var db = appProps.db();
    this.maxRetries = db != null && db.maxRetries() != null ? db.maxRetries() : 3;
    this.retryBaseDelayMs = db != null && db.retryBaseDelayMs() != null ? db.retryBaseDelayMs() : 1000L;
  }

  /** Determines if a database error is retryable */
  public boolean isRetryableError(Throwable error) {
    // Check the exception hierarchy for retryable conditions
    Throwable cause = error;
    while (cause != null) {
      if (isRetryableException(cause)) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private boolean isRetryableException(Throwable exception) {
    // Transient errors that might resolve with retry
    if (exception instanceof TransientDataAccessException) {
      log.debug("Detected transient data access exception: {}", exception.getMessage());
      return true;
    }

    // Connection timeouts
    if (exception instanceof QueryTimeoutException) {
      log.debug("Detected query timeout exception: {}", exception.getMessage());
      return true;
    }

    // SQL-specific retryable errors
    if (exception instanceof SQLException) {
      SQLException sqlEx = (SQLException) exception;
      String sqlState = sqlEx.getSQLState();
      int errorCode = sqlEx.getErrorCode();

      // PostgreSQL specific retryable errors
      if (isPostgreSQLRetryableError(sqlState, errorCode)) {
        log.debug(
            "Detected retryable PostgreSQL error - SQLState: {}, ErrorCode: {}",
            sqlState,
            errorCode);
        return true;
      }
    }

    // Connection pool exhaustion or similar
    if (exception.getMessage() != null) {
      String message = exception.getMessage().toLowerCase();
      if (message.contains("connection")
          && (message.contains("timeout")
              || message.contains("pool")
              || message.contains("refused"))) {
        log.debug("Detected connection-related error: {}", exception.getMessage());
        return true;
      }
    }

    return false;
  }

  private boolean isPostgreSQLRetryableError(String sqlState, int errorCode) {
    if (sqlState == null) {
      return false;
    }

    // Connection errors
    if (sqlState.startsWith("08")) {
      return true;
    }

    // Serialization failures (concurrent transactions)
    if ("40001".equals(sqlState)) {
      return true;
    }

    // Deadlock detected
    if ("40P01".equals(sqlState)) {
      return true;
    }

    // Lock timeout
    if ("55P03".equals(sqlState)) {
      return true;
    }

    // Connection failure during transaction
    if ("57P01".equals(sqlState) || "57P02".equals(sqlState) || "57P03".equals(sqlState)) {
      return true;
    }

    return false;
  }

  /** Determines if a database error is fatal (not retryable) */
  public boolean isFatalError(Throwable error) {
    Throwable cause = error;
    while (cause != null) {
      if (isFatalException(cause)) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private boolean isFatalException(Throwable exception) {
    // Data integrity violations - usually indicate business logic errors
    if (exception instanceof DataIntegrityViolationException) {
      log.debug("Detected data integrity violation: {}", exception.getMessage());
      return true;
    }

    // SQL syntax errors or schema issues
    if (exception instanceof SQLException) {
      SQLException sqlEx = (SQLException) exception;
      String sqlState = sqlEx.getSQLState();

      // Syntax errors, access violations, schema issues
      if (sqlState != null && (sqlState.startsWith("42") || sqlState.startsWith("2A"))) {
        log.debug("Detected fatal SQL error - SQLState: {}", sqlState);
        return true;
      }
    }

    return false;
  }

  /** Calculates retry delay using exponential backoff */
  public Duration calculateRetryDelay(int attemptNumber) {
    long delayMs = retryBaseDelayMs * (long) Math.pow(2, attemptNumber - 1);
    // Cap at 30 seconds to avoid excessive delays
    delayMs = Math.min(delayMs, 30000);
    return Duration.ofMillis(delayMs);
  }

  /** Gets the maximum number of retries allowed */
  public int getMaxRetries() {
    return maxRetries;
  }

  /** Creates a standardized error classification */
  public ErrorClassification classifyError(Throwable error) {
    if (isFatalError(error)) {
      return new ErrorClassification(ErrorType.FATAL, false, "Fatal database error");
    } else if (isRetryableError(error)) {
      return new ErrorClassification(ErrorType.RETRYABLE, true, "Transient database error");
    } else {
      return new ErrorClassification(ErrorType.UNKNOWN, false, "Unknown database error");
    }
  }

  public enum ErrorType {
    RETRYABLE,
    FATAL,
    UNKNOWN
  }

  public static class ErrorClassification {
    private final ErrorType type;
    private final boolean shouldRetry;
    private final String description;

    public ErrorClassification(ErrorType type, boolean shouldRetry, String description) {
      this.type = type;
      this.shouldRetry = shouldRetry;
      this.description = description;
    }

    public ErrorType getType() {
      return type;
    }

    public boolean shouldRetry() {
      return shouldRetry;
    }

    public String getDescription() {
      return description;
    }
  }
}
