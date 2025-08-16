package com.fourkites.event.scheduler.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Service for managing distributed locks using Redis. Ensures only one instance processes events
 * for a given timestamp or executes scheduled tasks.
 */
@Service
public class DistributedLockService {

  private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);
  private static final String TIMESTAMP_LOCK_PREFIX = "timestamp_lock:";
  private static final String TASK_LOCK_PREFIX = "task_lock:";
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final RedisTemplate<String, String> redisTemplate;

  public DistributedLockService(RedisTemplate<String, String> redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /**
   * Attempts to acquire a distributed lock for a scheduled task. Only one instance across the
   * cluster will be able to execute the task.
   */
  public LockResult acquireTaskLock(String taskName, long duration, TimeUnit timeUnit) {
    String lockKey = TASK_LOCK_PREFIX + taskName;
    String lockValue =
        "instance-" + System.getProperty("user.name") + "-" + System.currentTimeMillis();

    try {
      Boolean acquired =
          redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, duration, timeUnit);

      if (Boolean.TRUE.equals(acquired)) {
        log.debug("Successfully acquired task lock: {} (will execute)", taskName);
        return new LockResult(true, lockKey, lockValue);
      } else {
        log.debug("Failed to acquire task lock: {} (another instance will execute)", taskName);
        return new LockResult(false, lockKey, null);
      }

    } catch (Exception e) {
      log.error("Error acquiring task lock: {}", taskName, e);
      return new LockResult(false, lockKey, null);
    }
  }

  /**
   * Executes a task only if the distributed lock can be acquired. This ensures only ONE instance
   * executes the task across the entire cluster.
   */
  public boolean executeWithTaskLock(
      String taskName, long duration, TimeUnit timeUnit, Runnable task) {
    LockResult lockResult = acquireTaskLock(taskName, duration, timeUnit);

    if (!lockResult.isAcquired()) {
      log.debug("Skipping task '{}' - another instance is executing it", taskName);
      return false;
    }

    try {
      log.info("Executing task '{}' with distributed lock (this instance was chosen)", taskName);
      task.run();
      return true;

    } catch (Exception e) {
      log.error("Error executing task '{}' with distributed lock", taskName, e);
      throw e; // Re-throw to preserve original error handling

    } finally {
      releaseLock(lockResult);
    }
  }

  /** Attempts to acquire a distributed lock for a given timestamp. */
  public LockResult acquireLock(LocalDateTime timestamp, long duration, TimeUnit timeUnit) {
    String lockKey = TIMESTAMP_LOCK_PREFIX + timestamp.format(FORMATTER);
    String lockValue = Thread.currentThread().getName() + "-" + System.currentTimeMillis();

    try {
      Boolean acquired =
          redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, duration, timeUnit);

      if (Boolean.TRUE.equals(acquired)) {
        log.debug("Successfully acquired lock for timestamp: {}", timestamp);
        return new LockResult(true, lockKey, lockValue);
      } else {
        log.debug("Failed to acquire lock for timestamp: {} (already locked)", timestamp);
        return new LockResult(false, lockKey, null);
      }

    } catch (Exception e) {
      log.error("Error acquiring lock for timestamp: {}", timestamp, e);
      return new LockResult(false, lockKey, null);
    }
  }

  /** Releases a previously acquired lock. */
  public boolean releaseLock(LockResult lockResult) {
    if (!lockResult.isAcquired() || lockResult.getLockValue() == null) {
      return false;
    }

    try {
      // Use Lua script to ensure atomic check-and-delete
      String luaScript =
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "    return redis.call('del', KEYS[1]) "
              + "else "
              + "    return 0 "
              + "end";

      Long result =
          redisTemplate.execute(
              (RedisCallback<Long>)
                  connection ->
                      (Long)
                          connection.eval(
                              luaScript.getBytes(),
                              org.springframework.data.redis.connection.ReturnType.INTEGER,
                              1,
                              lockResult.getLockKey().getBytes(),
                              lockResult.getLockValue().getBytes()));

      boolean released = Long.valueOf(1).equals(result);
      if (released) {
        log.debug("Successfully released lock: {}", lockResult.getLockKey());
      } else {
        log.warn(
            "Failed to release lock: {} (may have expired or been released by another thread)",
            lockResult.getLockKey());
      }

      return released;

    } catch (Exception e) {
      log.error("Error releasing lock: {}", lockResult.getLockKey(), e);
      return false;
    }
  }

  /** Result of a lock acquisition attempt. */
  public static class LockResult {
    private final boolean acquired;
    private final String lockKey;
    private final String lockValue;

    public LockResult(boolean acquired, String lockKey, String lockValue) {
      this.acquired = acquired;
      this.lockKey = lockKey;
      this.lockValue = lockValue;
    }

    public boolean isAcquired() {
      return acquired;
    }

    public String getLockKey() {
      return lockKey;
    }

    public String getLockValue() {
      return lockValue;
    }
  }
}
