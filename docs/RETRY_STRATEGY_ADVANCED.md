# Retry Strategy - Advanced Configuration & Troubleshooting

## Table of Contents
- [Performance Tuning](#performance-tuning)
- [Thread Safety](#thread-safety)
- [Memory Management](#memory-management)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)
- [Migration Guide](#migration-guide)

## Performance Tuning

### Optimizing Retry Callbacks

Your retry callbacks are called from the native PIC layer, so performance is critical:

```java
public class HighPerformanceRetryCallbacks implements KvsRetryStrategyCallbacks {
    // Pre-compute common values to avoid calculations in hot path
    private static final long[] EXPONENTIAL_DELAYS = {1000, 2000, 4000, 8000, 16000, 30000};
    private static final Set<Integer> RETRYABLE_ERRORS = Set.of(
        0x52000025, 0x52000026, 0x52000027, 0x15000005, 0x15000006, 0x15000007
    );
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        // Fast path: check attempt limit first
        if (attemptNumber > 3) return false;
        
        // Fast lookup for retryable errors
        return RETRYABLE_ERRORS.contains(errorCode);
    }
    
    @Override
    public long getRetryDelay(int attemptNumber, int errorCode) {
        // Use pre-computed delays for better performance
        int index = Math.min(attemptNumber - 1, EXPONENTIAL_DELAYS.length - 1);
        return EXPONENTIAL_DELAYS[index];
    }
    
    @Override
    public int getMaxRetryAttempts(String operationType) {
        return 3; // Constant time
    }
}
```

### Reducing Logging Overhead

```java
public class OptimizedLoggingCallbacks implements KvsRetryStrategyCallbacks {
    private static final Logger log = LogManager.getLogger(OptimizedLoggingCallbacks.class);
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        boolean shouldRetry = attemptNumber <= 3 && isRetryableError(errorCode);
        
        // Only log when necessary
        if (log.isDebugEnabled()) {
            log.debug("Retry decision: attempt={}, error=0x{}, operation={}, result={}", 
                     attemptNumber, Integer.toHexString(errorCode), operationType, shouldRetry);
        } else if (!shouldRetry && attemptNumber == 1) {
            // Always log first failure that won't be retried
            log.warn("Operation {} failed with non-retryable error: 0x{}", 
                    operationType, Integer.toHexString(errorCode));
        }
        
        return shouldRetry;
    }
    
    // ... other methods
}
```

### Batch Configuration for Multiple Streams

```java
public class StreamSpecificRetryStrategy {
    private final Map<String, KvsRetryStrategy> strategyByStreamName = new ConcurrentHashMap<>();
    
    public KvsRetryStrategy getStrategyForStream(String streamName) {
        return strategyByStreamName.computeIfAbsent(streamName, this::createStrategyForStream);
    }
    
    private KvsRetryStrategy createStrategyForStream(String streamName) {
        if (streamName.contains("critical")) {
            // More aggressive retries for critical streams
            return new KvsRetryStrategy(new DefaultKvsRetryStrategyCallbacks(5, 500L, 15000L));
        } else if (streamName.contains("batch")) {
            // Fewer retries for batch processing streams
            return new KvsRetryStrategy(new DefaultKvsRetryStrategyCallbacks(2, 2000L, 10000L));
        } else {
            // Default strategy
            return new KvsRetryStrategy(new DefaultKvsRetryStrategyCallbacks());
        }
    }
}
```

## Thread Safety

### Understanding Threading Model

The retry callbacks can be called from multiple native threads simultaneously:

```
Thread 1 (Stream A) ──► shouldRetry() ──► Your Callback
Thread 2 (Stream B) ──► shouldRetry() ──► Your Callback  ← Concurrent!
Thread 3 (Stream C) ──► getRetryDelay() ──► Your Callback
```

### Thread-Safe Implementation

```java
public class ThreadSafeRetryCallbacks implements KvsRetryStrategyCallbacks {
    // Use thread-safe collections and atomic operations
    private final AtomicLong totalRetries = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicInteger> retriesByOperation = new ConcurrentHashMap<>();
    private final ThreadLocal<Random> randomGenerator = ThreadLocal.withInitial(Random::new);
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        // Atomic increment for statistics
        totalRetries.incrementAndGet();
        retriesByOperation.computeIfAbsent(operationType, k -> new AtomicInteger(0)).incrementAndGet();
        
        // Thread-safe retry logic
        return attemptNumber <= getMaxRetryAttempts(operationType) && isRetryableError(errorCode);
    }
    
    @Override
    public long getRetryDelay(int attemptNumber, int errorCode) {
        long baseDelay = 1000L * (1L << (attemptNumber - 1));
        
        // Thread-local random for jitter (avoids synchronization)
        double jitter = 0.75 + (randomGenerator.get().nextDouble() * 0.5);
        return (long) (Math.min(baseDelay, 30000L) * jitter);
    }
    
    @Override
    public int getMaxRetryAttempts(String operationType) {
        return 3; // Immutable, inherently thread-safe
    }
    
    private boolean isRetryableError(int errorCode) {
        // Immutable logic, thread-safe
        return errorCode == 0x52000025 || errorCode == 0x52000026 || errorCode == 0x52000027;
    }
    
    // Thread-safe metrics access
    public long getTotalRetries() {
        return totalRetries.get();
    }
    
    public Map<String, Integer> getRetriesByOperation() {
        Map<String, Integer> snapshot = new HashMap<>();
        retriesByOperation.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }
}
```

### Avoiding Common Threading Issues

```java
// ❌ DON'T: Non-thread-safe implementation
public class BadRetryCallbacks implements KvsRetryStrategyCallbacks {
    private int totalRetries = 0; // Race condition!
    private Map<String, Integer> stats = new HashMap<>(); // Not thread-safe!
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        totalRetries++; // Race condition!
        stats.put(operationType, stats.getOrDefault(operationType, 0) + 1); // Race condition!
        return attemptNumber <= 3;
    }
}

// ✅ DO: Thread-safe implementation
public class GoodRetryCallbacks implements KvsRetryStrategyCallbacks {
    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> stats = new ConcurrentHashMap<>();
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        totalRetries.incrementAndGet();
        stats.computeIfAbsent(operationType, k -> new AtomicInteger(0)).incrementAndGet();
        return attemptNumber <= 3;
    }
}
```

## Memory Management

### Avoiding Memory Leaks

The JNI layer manages global references to your callback objects. Here's how to avoid leaks:

```java
public class MemoryEfficientCallbacks implements KvsRetryStrategyCallbacks {
    // ✅ Use primitive collections when possible
    private final TIntIntHashMap errorCounts = new TIntIntHashMap(); // GNU Trove library
    
    // ✅ Limit cache sizes
    private final Map<String, Integer> operationLimits = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        // ✅ Avoid creating unnecessary objects in hot path
        if (attemptNumber > 3) return false;
        
        // ✅ Use efficient data structures
        synchronized (errorCounts) {
            errorCounts.adjustOrPutValue(errorCode, 1, 1);
        }
        
        return isRetryableError(errorCode);
    }
    
    @Override
    public long getRetryDelay(int attemptNumber, int errorCode) {
        // ✅ Avoid object allocation in calculations
        return Math.min(1000L << (attemptNumber - 1), 30000L);
    }
    
    @Override
    public int getMaxRetryAttempts(String operationType) {
        // ✅ Cache with size limit
        return operationLimits.computeIfAbsent(operationType, this::computeMaxAttempts);
    }
    
    private int computeMaxAttempts(String operationType) {
        // ✅ Prevent unbounded cache growth
        if (operationLimits.size() > MAX_CACHE_SIZE) {
            operationLimits.clear(); // Simple eviction strategy
        }
        
        // Your logic here
        return operationType.startsWith("critical") ? 5 : 3;
    }
    
    private boolean isRetryableError(int errorCode) {
        // ✅ Use switch for efficiency (JVM optimizes this)
        switch (errorCode) {
            case 0x52000025:
            case 0x52000026:
            case 0x52000027:
                return true;
            default:
                return false;
        }
    }
}
```

### Resource Cleanup

```java
public class ResourceAwareCallbacks implements KvsRetryStrategyCallbacks, AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    public ResourceAwareCallbacks() {
        // Schedule periodic cleanup
        scheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.HOURS);
    }
    
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        if (closed.get()) {
            return false; // Don't retry if shutting down
        }
        
        return attemptNumber <= 3 && isRetryableError(errorCode);
    }
    
    private void cleanup() {
        // Periodic cleanup of internal state
        // Clear old statistics, reset counters, etc.
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // ... other methods
}
```

## Troubleshooting

### Common Issues and Solutions

#### 1. Callbacks Not Being Called

**Symptoms:**
- Retry logic never executes
- Operations fail without retry attempts

**Diagnosis:**
```java
public class DiagnosticCallbacks implements KvsRetryStrategyCallbacks {
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        System.out.println("shouldRetry called!"); // Add this to verify calls
        return true;
    }
    
    // ... other methods with similar diagnostic prints
}
```

**Solutions:**
1. Verify retry strategy is set in ClientInfo:
```java
ClientInfo clientInfo = new ClientInfo(/* ... */, retryStrategy); // Make sure this is not null
```

2. Check that callbacks object is not null:
```java
KvsRetryStrategy strategy = new KvsRetryStrategy(callbacks);
assert strategy.getCallbacks() != null;
```

3. Enable debug logging:
```xml
<Logger name="com.amazonaws.kinesisvideo" level="DEBUG"/>
```

#### 2. JNI Errors

**Symptoms:**
- `UnsatisfiedLinkError`
- Native crashes
- Method not found errors

**Solutions:**
1. Verify method signatures match:
```java
// Java method signature must match JNI expectations
public boolean shouldRetry(int attemptNumber, int errorCode, String operationType)
//                         ^^^            ^^^            ^^^^^^
//                         jint           jint           jstring
```

2. Check JNI library loading:
```bash
java -Djava.library.path=/path/to/jni/lib -cp your-app.jar YourMainClass
```

3. Verify native library architecture matches JVM:
```bash
file /path/to/libKinesisVideoProducerJNI.dylib  # Should match your JVM architecture
```

#### 3. Memory Issues

**Symptoms:**
- OutOfMemoryError
- Native memory leaks
- Performance degradation over time

**Solutions:**
1. Monitor JNI global references:
```java
// Add logging to track reference lifecycle
public class ReferenceTrackingCallbacks implements KvsRetryStrategyCallbacks {
    private static final AtomicLong instanceCount = new AtomicLong(0);
    
    public ReferenceTrackingCallbacks() {
        long count = instanceCount.incrementAndGet();
        System.out.println("Created callback instance #" + count);
    }
    
    // Implement finalize for debugging (remove in production)
    @Override
    protected void finalize() throws Throwable {
        long count = instanceCount.decrementAndGet();
        System.out.println("Finalized callback instance, remaining: " + count);
        super.finalize();
    }
}
```

2. Use memory profiling:
```bash
java -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -Xloggc:gc.log YourApp
```

#### 4. Performance Issues

**Symptoms:**
- High latency in retry decisions
- CPU spikes during retries
- Slow streaming performance

**Solutions:**
1. Profile callback performance:
```java
public class ProfilingCallbacks implements KvsRetryStrategyCallbacks {
    @Override
    public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
        long start = System.nanoTime();
        try {
            // Your retry logic here
            return attemptNumber <= 3;
        } finally {
            long duration = System.nanoTime() - start;
            if (duration > 1_000_000) { // > 1ms
                System.out.printf("Slow shouldRetry: %d ns%n", duration);
            }
        }
    }
}
```

2. Optimize hot paths:
```java
// ❌ Slow: String operations in hot path
public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
    return attemptNumber <= 3 && operationType.startsWith("critical");
}

// ✅ Fast: Pre-compute or cache results
private static final Set<String> CRITICAL_OPERATIONS = Set.of("criticalOp1", "criticalOp2");
public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
    return attemptNumber <= 3 && CRITICAL_OPERATIONS.contains(operationType);
}
```

### Debug Configuration

Enable comprehensive debugging:

```java
public class DebugRetryStrategy {
    public static KvsRetryStrategy createDebugStrategy() {
        return new KvsRetryStrategy(new DebugRetryCallbacks());
    }
    
    static class DebugRetryCallbacks implements KvsRetryStrategyCallbacks {
        private final AtomicLong callCount = new AtomicLong(0);
        
        @Override
        public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
            long callId = callCount.incrementAndGet();
            
            System.out.printf("[DEBUG-%d] shouldRetry called:%n", callId);
            System.out.printf("  Thread: %s%n", Thread.currentThread().getName());
            System.out.printf("  Attempt: %d%n", attemptNumber);
            System.out.printf("  Error: 0x%x (%d)%n", errorCode, errorCode);
            System.out.printf("  Operation: %s%n", operationType);
            System.out.printf("  Stack trace:%n");
            
            // Print abbreviated stack trace
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 2; i < Math.min(stack.length, 7); i++) {
                System.out.printf("    at %s%n", stack[i]);
            }
            
            boolean result = attemptNumber <= 3 && isRetryableError(errorCode);
            System.out.printf("  Result: %s%n", result);
            System.out.println();
            
            return result;
        }
        
        @Override
        public long getRetryDelay(int attemptNumber, int errorCode) {
            long delay = 1000L * attemptNumber;
            System.out.printf("[DEBUG] getRetryDelay: attempt=%d, delay=%dms%n", 
                             attemptNumber, delay);
            return delay;
        }
        
        @Override
        public int getMaxRetryAttempts(String operationType) {
            System.out.printf("[DEBUG] getMaxRetryAttempts: operation=%s, max=3%n", operationType);
            return 3;
        }
        
        private boolean isRetryableError(int errorCode) {
            boolean retryable = errorCode == 0x52000025 || errorCode == 0x52000026;
            System.out.printf("[DEBUG] isRetryableError: 0x%x -> %s%n", errorCode, retryable);
            return retryable;
        }
    }
}
```

### Logging Configuration

Add detailed logging to your `log4j2.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
        
        <!-- Separate file for retry strategy logs -->
        <File name="RetryLog" fileName="retry-strategy.log">
            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </File>
    </Appenders>
    
    <Loggers>
        <!-- Debug retry strategy specifically -->
        <Logger name="com.amazonaws.kinesisvideo.producer.DefaultKvsRetryStrategyCallbacks" 
                level="DEBUG" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="RetryLog"/>
        </Logger>
        
        <!-- General KVS logging -->
        <Logger name="com.amazonaws.kinesisvideo" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
        </Logger>
        
        <Root level="WARN">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

This advanced guide provides the depth needed for production deployments and troubleshooting complex scenarios. The examples show real-world patterns and common pitfalls to avoid.
