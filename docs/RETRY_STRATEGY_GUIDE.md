# Kinesis Video Streams Producer SDK - Retry Strategy Guide

## Table of Contents
- [Overview](#overview)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Configuration](#configuration)
- [Examples](#examples)
- [Advanced Usage](#advanced-usage)
- [Troubleshooting](#troubleshooting)

## Overview

The Retry Strategy feature in the Amazon Kinesis Video Streams Producer SDK allows you to customize how the SDK handles failures and retries. This is essential for building resilient streaming applications that can handle network issues, service timeouts, and other transient failures.

### What Problems Does This Solve?

- **Network Instability**: Automatically retry when network connections fail
- **Service Timeouts**: Handle temporary service unavailability
- **Rate Limiting**: Implement backoff strategies for rate-limited operations
- **Custom Logic**: Apply business-specific retry rules

### Key Benefits

- 🔄 **Automatic Retries**: PIC layer handles retry timing and execution
- 🎛️ **Full Control**: Customize retry decisions and delays
- 📊 **Observability**: Built-in logging and metrics
- 🛡️ **Resilience**: Improve application reliability

## Quick Start

### 1. Basic Usage (Recommended for New Developers)

```java
import com.amazonaws.kinesisvideo.producer.*;

// Create default retry strategy (good for most use cases)
DefaultKvsRetryStrategyCallbacks callbacks = new DefaultKvsRetryStrategyCallbacks();
KvsRetryStrategy retryStrategy = new KvsRetryStrategy(callbacks);

// Use in your client configuration
ClientInfo clientInfo = new ClientInfo(
    10000L, // createClientTimeout
    20000L, // createStreamTimeout
    30000L, // stopStreamTimeout
    40000L, // offlineBufferAvailabilityTimeout
    4,      // logLevel
    true,   // logMetric
    ClientInfo.AutomaticStreamingFlags.AUTOMATIC_STREAMING_INTERMITTENT_PRODUCER,
    50000L, // serviceCallCompletionTimeout
    60000L, // serviceCallConnectionTimeout
    70000L, // metricLoggingPeriod (NEW)
    80000L, // reservedCallbackPeriod (NEW)
    retryStrategy // kvsRetryStrategy (NEW)
);

// That's it! The SDK will now automatically retry failed operations
```

### 2. What Happens Automatically

When you configure a retry strategy:

1. **PIC Layer Detects Failure** → Network timeout, service error, etc.
2. **Calls Your Retry Logic** → Should we retry? How long to wait?
3. **Executes Decision** → Retries automatically or gives up
4. **Logs Results** → You can monitor retry behavior

## Architecture

### System Flow Diagram

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Your Java     │    │   PIC Layer     │    │  AWS Service    │
│  Application    │    │  (Native C++)   │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         │ 1. Configure Retry    │                       │
         │    Strategy           │                       │
         ├──────────────────────►│                       │
         │                       │                       │
         │                       │ 2. Attempt Operation  │
         │                       ├──────────────────────►│
         │                       │                       │
         │                       │ 3. Operation Fails    │
         │                       │◄──────────────────────┤
         │                       │                       │
         │ 4. shouldRetry()?     │                       │
         │◄──────────────────────┤                       │
         │                       │                       │
         │ 5. Return: true       │                       │
         ├──────────────────────►│                       │
         │                       │                       │
         │ 6. getRetryDelay()?   │                       │
         │◄──────────────────────┤                       │
         │                       │                       │
         │ 7. Return: 2000ms     │                       │
         ├──────────────────────►│                       │
         │                       │                       │
         │                       │ 8. Wait 2000ms        │
         │                       │                       │
         │                       │ 9. Retry Operation    │
         │                       ├──────────────────────►│
         │                       │                       │
         │                       │ 10. Success!          │
         │                       │◄──────────────────────┤
```

### Component Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Java Layer                               │
├─────────────────────────────────────────────────────────────┤
│  KvsRetryStrategyCallbacks (Interface)                      │
│  ├── shouldRetry(attemptNumber, errorCode, operationType)   │
│  ├── getRetryDelay(attemptNumber, errorCode)               │
│  └── getMaxRetryAttempts(operationType)                    │
│                                                             │
│  DefaultKvsRetryStrategyCallbacks (Implementation)          │
│  ├── Exponential backoff with jitter                       │
│  ├── Configurable max attempts                             │
│  └── Built-in retryable error detection                    │
│                                                             │
│  KvsRetryStrategy (Configuration)                           │
│  ├── RetryStrategyType enum                                 │
│  └── Callbacks reference                                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ JNI Bridge
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Native Layer (C++)                        │
├─────────────────────────────────────────────────────────────┤
│  retryStrategyCallbackFunc()                                │
│  ├── Receives PIC retry context                             │
│  ├── Calls Java shouldRetry() via JNI                      │
│  ├── Calls Java getRetryDelay() via JNI                    │
│  └── Returns decision to PIC                               │
│                                                             │
│  PIC Layer (Producer Implementation in C)                   │
│  ├── Detects operation failures                             │
│  ├── Calls retry strategy function                          │
│  ├── Implements retry timing and logic                      │
│  └── Manages retry attempts                                 │
└─────────────────────────────────────────────────────────────┘
```

## Configuration

### New ClientInfo Parameters

The `ClientInfo` class now includes three new parameters:

```java
public ClientInfo(
    // ... existing parameters ...
    long metricLoggingPeriod,      // NEW: How often to log metrics (ms)
    long reservedCallbackPeriod,   // NEW: Reserved for future use (ms)
    KvsRetryStrategy kvsRetryStrategy  // NEW: Your retry strategy
)
```

### Retry Strategy Types

```java
public enum RetryStrategyType {
    EXPONENTIAL_BACKOFF_WAIT,  // 2^n backoff (recommended)
    LINEAR_BACKOFF_WAIT,       // Linear increase
    FIXED_DELAY_WAIT,          // Same delay each time
    NO_RETRY                   // Disable retries
}
```

### Default Configuration

```java
// Default retry strategy settings
DefaultKvsRetryStrategyCallbacks defaults = new DefaultKvsRetryStrategyCallbacks();
// - Max retry attempts: 3
// - Base delay: 1000ms (1 second)
// - Max delay: 30000ms (30 seconds)
// - Strategy: Exponential backoff

// Custom configuration
DefaultKvsRetryStrategyCallbacks custom = new DefaultKvsRetryStrategyCallbacks(
    5,      // maxRetryAttempts
    500L,   // baseDelayMs
    15000L  // maxDelayMs
);
```

## Examples

### Example 1: Default Configuration (Beginner)

Perfect for getting started - works well for most applications:

```java
public class BasicRetryExample {
    public static void main(String[] args) {
        // Step 1: Create default retry strategy
        DefaultKvsRetryStrategyCallbacks callbacks = new DefaultKvsRetryStrategyCallbacks();
        KvsRetryStrategy retryStrategy = new KvsRetryStrategy(callbacks);
        
        // Step 2: Create ClientInfo with retry strategy
        ClientInfo clientInfo = new ClientInfo(
            10000L, // createClientTimeout
            20000L, // createStreamTimeout  
            30000L, // stopStreamTimeout
            40000L, // offlineBufferAvailabilityTimeout
            4,      // logLevel
            true,   // logMetric
            ClientInfo.AutomaticStreamingFlags.AUTOMATIC_STREAMING_INTERMITTENT_PRODUCER,
            50000L, // serviceCallCompletionTimeout
            60000L, // serviceCallConnectionTimeout
            0L,     // metricLoggingPeriod (0 = default)
            0L,     // reservedCallbackPeriod (0 = default)
            retryStrategy // Your retry strategy
        );
        
        // Step 3: Use ClientInfo to create your KVS client
        // The retry strategy is now active!
        System.out.println("Retry strategy configured successfully!");
        System.out.println("Max attempts: " + callbacks.getMaxRetryAttempts("test"));
    }
}
```

### Example 2: Custom Retry Logic (Intermediate)

For applications with specific retry requirements:

```java
public class CustomRetryExample {
    public static void main(String[] args) {
        // Create custom retry callbacks
        KvsRetryStrategyCallbacks customCallbacks = new KvsRetryStrategyCallbacks() {
            @Override
            public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
                System.out.printf("Retry decision: attempt=%d, error=0x%x, operation=%s%n", 
                                attemptNumber, errorCode, operationType);
                
                // Custom logic: retry up to 5 times for network errors only
                if (attemptNumber > 5) {
                    System.out.println("Max attempts reached, giving up");
                    return false;
                }
                
                // Only retry specific error codes
                boolean isNetworkError = (errorCode == 0x52000025 || // Network timeout
                                        errorCode == 0x52000026 || // Read timeout
                                        errorCode == 0x52000027);  // Write timeout
                
                if (isNetworkError) {
                    System.out.println("Network error detected, will retry");
                    return true;
                } else {
                    System.out.println("Non-retryable error, giving up");
                    return false;
                }
            }
            
            @Override
            public long getRetryDelay(int attemptNumber, int errorCode) {
                // Custom delay: start with 1 second, double each time, max 10 seconds
                long delay = Math.min(1000 * (1L << (attemptNumber - 1)), 10000);
                System.out.printf("Retry delay for attempt %d: %d ms%n", attemptNumber, delay);
                return delay;
            }
            
            @Override
            public int getMaxRetryAttempts(String operationType) {
                // Different limits for different operations
                switch (operationType) {
                    case "putMedia": return 5;      // More retries for media upload
                    case "createStream": return 2;  // Fewer retries for stream creation
                    default: return 3;
                }
            }
        };
        
        // Use custom callbacks with linear backoff strategy
        KvsRetryStrategy retryStrategy = new KvsRetryStrategy(
            KvsRetryStrategy.RetryStrategyType.LINEAR_BACKOFF_WAIT,
            customCallbacks
        );
        
        // Test the retry logic
        System.out.println("Testing custom retry logic:");
        customCallbacks.shouldRetry(1, 0x52000025, "putMedia");  // Should retry
        customCallbacks.shouldRetry(1, 0x99999999, "putMedia");  // Should not retry
        customCallbacks.shouldRetry(6, 0x52000025, "putMedia");  // Max attempts exceeded
    }
}
```

### Example 3: Production Configuration (Advanced)

Enterprise-ready configuration with comprehensive error handling:

```java
public class ProductionRetryExample {
    private static final Logger log = LogManager.getLogger(ProductionRetryExample.class);
    
    public static KvsRetryStrategy createProductionRetryStrategy() {
        return new KvsRetryStrategy(
            KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT,
            new ProductionRetryCallbacks()
        );
    }
    
    static class ProductionRetryCallbacks implements KvsRetryStrategyCallbacks {
        private static final int MAX_ATTEMPTS = 3;
        private static final long BASE_DELAY_MS = 1000;
        private static final long MAX_DELAY_MS = 30000;
        
        // Metrics tracking
        private final AtomicLong totalRetries = new AtomicLong(0);
        private final AtomicLong successfulRetries = new AtomicLong(0);
        
        @Override
        public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
            totalRetries.incrementAndGet();
            
            if (attemptNumber > MAX_ATTEMPTS) {
                log.warn("Max retry attempts ({}) exceeded for operation: {}, error: 0x{}", 
                        MAX_ATTEMPTS, operationType, Integer.toHexString(errorCode));
                return false;
            }
            
            boolean isRetryable = isRetryableError(errorCode);
            
            if (isRetryable) {
                log.info("Retrying {} (attempt {}/{}) due to retryable error: 0x{}", 
                        operationType, attemptNumber, MAX_ATTEMPTS, Integer.toHexString(errorCode));
            } else {
                log.warn("Not retrying {} due to non-retryable error: 0x{}", 
                        operationType, Integer.toHexString(errorCode));
            }
            
            return isRetryable;
        }
        
        @Override
        public long getRetryDelay(int attemptNumber, int errorCode) {
            // Exponential backoff with jitter to prevent thundering herd
            long delay = Math.min(BASE_DELAY_MS * (1L << (attemptNumber - 1)), MAX_DELAY_MS);
            
            // Add jitter (±25%)
            double jitter = 0.75 + (Math.random() * 0.5);
            delay = (long) (delay * jitter);
            
            log.debug("Calculated retry delay for attempt {}: {}ms", attemptNumber, delay);
            return delay;
        }
        
        @Override
        public int getMaxRetryAttempts(String operationType) {
            // Operation-specific retry limits
            switch (operationType) {
                case "putMedia":
                    return 5; // Media upload is more tolerant of retries
                case "createStream":
                case "describeStream":
                    return 3; // Control plane operations
                case "getStreamingEndpoint":
                case "getStreamingToken":
                    return 2; // Authentication operations
                default:
                    return MAX_ATTEMPTS;
            }
        }
        
        private boolean isRetryableError(int errorCode) {
            switch (errorCode) {
                // Network-related errors
                case 0x52000025: // STATUS_NETWORK_CONNECTION_TIMEOUT
                case 0x52000026: // STATUS_NETWORK_READ_TIMEOUT  
                case 0x52000027: // STATUS_NETWORK_WRITE_TIMEOUT
                    
                // Service-related errors
                case 0x15000005: // STATUS_SERVICE_CALL_TIMEOUT_ERROR
                case 0x15000006: // STATUS_SERVICE_CALL_NETWORK_CONNECTION_TIMEOUT
                case 0x15000007: // STATUS_SERVICE_CALL_NETWORK_READ_TIMEOUT
                    
                // HTTP status codes (if applicable)
                case 429: // Too Many Requests
                case 500: // Internal Server Error
                case 502: // Bad Gateway
                case 503: // Service Unavailable
                case 504: // Gateway Timeout
                    return true;
                    
                // Non-retryable errors
                case 400: // Bad Request
                case 401: // Unauthorized
                case 403: // Forbidden
                case 404: // Not Found
                default:
                    return false;
            }
        }
        
        // Metrics methods
        public long getTotalRetries() { return totalRetries.get(); }
        public long getSuccessfulRetries() { return successfulRetries.get(); }
        public void recordSuccessfulRetry() { successfulRetries.incrementAndGet(); }
    }
    
    public static void main(String[] args) {
        KvsRetryStrategy retryStrategy = createProductionRetryStrategy();
        
        ClientInfo clientInfo = new ClientInfo(
            15000L, // createClientTimeout - increased for production
            30000L, // createStreamTimeout - increased for production
            45000L, // stopStreamTimeout - increased for production
            60000L, // offlineBufferAvailabilityTimeout - increased for production
            3,      // logLevel - INFO level for production
            true,   // logMetric - enable metrics
            ClientInfo.AutomaticStreamingFlags.AUTOMATIC_STREAMING_INTERMITTENT_PRODUCER,
            120000L, // serviceCallCompletionTimeout - 2 minutes
            30000L,  // serviceCallConnectionTimeout - 30 seconds
            300000L, // metricLoggingPeriod - log metrics every 5 minutes
            0L,      // reservedCallbackPeriod - not used yet
            retryStrategy
        );
        
        log.info("Production retry strategy configured:");
        log.info("- Strategy Type: {}", retryStrategy.getRetryStrategyType());
        log.info("- Max Attempts: {}", retryStrategy.getCallbacks().getMaxRetryAttempts("putMedia"));
        log.info("- Configuration ready for production use");
    }
}
```

## Advanced Usage

### Monitoring Retry Behavior

```java
public class RetryMonitoringExample {
    public static void main(String[] args) {
        // Create callbacks with monitoring
        KvsRetryStrategyCallbacks monitoredCallbacks = new KvsRetryStrategyCallbacks() {
            private final Map<String, AtomicInteger> retryCountsByOperation = new ConcurrentHashMap<>();
            private final Map<Integer, AtomicInteger> retryCountsByError = new ConcurrentHashMap<>();
            
            @Override
            public boolean shouldRetry(int attemptNumber, int errorCode, String operationType) {
                // Track retry statistics
                retryCountsByOperation.computeIfAbsent(operationType, k -> new AtomicInteger(0)).incrementAndGet();
                retryCountsByError.computeIfAbsent(errorCode, k -> new AtomicInteger(0)).incrementAndGet();
                
                // Log retry patterns
                if (attemptNumber == 1) {
                    System.out.printf("First retry for %s due to error 0x%x%n", operationType, errorCode);
                }
                
                return attemptNumber <= 3 && isRetryableError(errorCode);
            }
            
            @Override
            public long getRetryDelay(int attemptNumber, int errorCode) {
                return 1000 * attemptNumber; // Simple linear backoff
            }
            
            @Override
            public int getMaxRetryAttempts(String operationType) {
                return 3;
            }
            
            private boolean isRetryableError(int errorCode) {
                return errorCode == 0x52000025 || errorCode == 0x52000026;
            }
            
            // Monitoring methods
            public void printStatistics() {
                System.out.println("Retry Statistics:");
                retryCountsByOperation.forEach((op, count) -> 
                    System.out.printf("  %s: %d retries%n", op, count.get()));
                retryCountsByError.forEach((error, count) -> 
                    System.out.printf("  Error 0x%x: %d retries%n", error, count.get()));
            }
        };
        
        // Test the monitoring
        monitoredCallbacks.shouldRetry(1, 0x52000025, "putMedia");
        monitoredCallbacks.shouldRetry(2, 0x52000025, "putMedia");
        monitoredCallbacks.shouldRetry(1, 0x52000026, "createStream");
        
        // Print statistics
        ((RetryMonitoringExample) null).printStatistics(); // This would be called on your actual callback instance
    }
}
```

This documentation provides a comprehensive guide that's accessible to new developers while providing the depth experienced developers need. The examples progress from simple to complex, and the architecture diagrams help visualize how the system works.

Would you like me to continue with the remaining sections (Troubleshooting, Performance Tuning, etc.) in separate files?
