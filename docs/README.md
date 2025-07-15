# Kinesis Video Streams Producer SDK - Retry Strategy Documentation

## 📚 Overview

The retry strategy feature allows you to configure how the Amazon Kinesis Video Streams Producer SDK handles retries when operations fail. This uses **PIC's built-in retry strategies** with **full configuration control** from Java!

## 🚀 Quick Start (30 seconds!)

```java
// Option 1: All PIC defaults (recommended for most cases)
KvsRetryStrategy retryStrategy = new KvsRetryStrategy();

// Option 2: Custom configuration (use 0 for PIC defaults)
ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
    3,      // maxRetryCount - retry up to 3 times
    0,      // maxRetryWaitTimeMs - use PIC default (16 seconds)
    1000,   // retryFactorTimeMs - start with 1 second delay
    0,      // minTimeToResetRetryStateMs - use PIC default (90 seconds)
    null,   // jitterType - use PIC default (FULL_JITTER)
    0       // jitterFactor - use PIC default
);
KvsRetryStrategy customStrategy = new KvsRetryStrategy(config);

// Add to ClientInfo
ClientInfo clientInfo = new ClientInfo(
    /* your existing parameters */,
    0L,           // metricLoggingPeriod (NEW)
    0L,           // reservedCallbackPeriod (NEW)
    retryStrategy // kvsRetryStrategy (NEW)
);

// That's it! PIC uses your configuration with smart defaults
```

## 🎯 Key Features

### ✨ **What You Get**
- 🔄 **Automatic Retries**: PIC layer handles all retry logic and timing
- 🎛️ **Smart Defaults**: Use 0/null to let PIC handle proven default values
- 📊 **Full Control**: Customize any parameter you need, default the rest
- 🛡️ **Production Ready**: Battle-tested retry algorithms from PIC
- 🔧 **Clean Configuration**: No hardcoded constants, PIC manages defaults
- 📈 **Future-Proof**: Automatic updates when PIC defaults improve

### 🎛️ **Available Retry Strategies**

| Strategy | Value | Behavior | Configuration |
|----------|-------|----------|---------------|
| `EXPONENTIAL_BACKOFF_WAIT` | 0x01 | Automatic retries with exponential backoff | **Fully configurable with smart defaults** |
| `DISABLED` | 0x00 | No automatic retries | No configuration needed |

### 🔧 **Configuration Parameters**

| Parameter | Default Behavior | Description | Example |
|-----------|------------------|-------------|---------|
| `maxRetryCount` | 0 → PIC default (infinite) | Maximum retry attempts | `3` |
| `maxRetryWaitTimeMs` | 0 → PIC default (16000ms) | Maximum wait between retries | `8000` |
| `retryFactorTimeMs` | 0 → PIC default (1000ms) | Base delay for exponential calculation | `500` |
| `minTimeToResetRetryStateMs` | 0 → PIC default (90000ms) | Time to reset retry state | `60000` |
| `jitterType` | null → PIC default (FULL_JITTER) | Type of jitter to add | `FIXED_JITTER` |
| `jitterFactor` | 0 → PIC default | Jitter amount (for FIXED_JITTER) | `200` |

**Key Insight**: Use `0` for numeric values and `null` for enums to let PIC handle defaults!

## 📋 Configuration Examples

### Basic Usage
```java
// All PIC defaults (recommended)
KvsRetryStrategy strategy = new KvsRetryStrategy();

// Disable retries
KvsRetryStrategy strategy = new KvsRetryStrategy(
    KvsRetryStrategy.RetryStrategyType.DISABLED);
```

### Smart Configuration (Mix Custom + PIC Defaults)
```java
// Customize only what you need, let PIC handle the rest
ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
    3,      // maxRetryCount - retry up to 3 times
    0,      // maxRetryWaitTimeMs - use PIC default (16 seconds)
    1000,   // retryFactorTimeMs - start with 1 second delay
    0,      // minTimeToResetRetryStateMs - use PIC default (90 seconds)
    null,   // jitterType - use PIC default (FULL_JITTER)
    0       // jitterFactor - use PIC default
);

KvsRetryStrategy strategy = new KvsRetryStrategy(config);
```

### Full Custom Configuration
```java
// Complete control over all parameters
ExponentialBackoffRetryStrategyConfig fullConfig = new ExponentialBackoffRetryStrategyConfig(
    5,      // maxRetryCount
    10000,  // maxRetryWaitTimeMs
    500,    // retryFactorTimeMs
    120000, // minTimeToResetRetryStateMs
    ExponentialBackoffRetryStrategyConfig.JitterType.FIXED_JITTER,
    300     // jitterFactor
);

KvsRetryStrategy strategy = new KvsRetryStrategy(fullConfig);
```

### Jitter Types
```java
// Full jitter (recommended) - random jitter up to full delay
ExponentialBackoffRetryStrategyConfig fullJitter = new ExponentialBackoffRetryStrategyConfig(
    3, 8000, 1000, 0, // Use PIC default for reset time
    ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER, 
    0 // jitterFactor unused for FULL_JITTER
);

// Fixed jitter - random jitter up to jitterFactor
ExponentialBackoffRetryStrategyConfig fixedJitter = new ExponentialBackoffRetryStrategyConfig(
    3, 8000, 1000, 0, // Use PIC default for reset time
    ExponentialBackoffRetryStrategyConfig.JitterType.FIXED_JITTER,
    200 // Add up to 200ms random jitter
);

// No jitter - exact exponential backoff timing
ExponentialBackoffRetryStrategyConfig noJitter = new ExponentialBackoffRetryStrategyConfig(
    3, 8000, 1000, 0, // Use PIC default for reset time
    ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER,
    0 // jitterFactor unused for NO_JITTER
);
```

### Complete ClientInfo Setup
```java
// Create custom retry configuration
ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
    5,      // Retry up to 5 times
    10000,  // Max wait 10 seconds
    1000,   // Start with 1 second delay
    120000, // Reset after 2 minutes
    ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER,
    0       // Full jitter doesn't use factor
);

KvsRetryStrategy retryStrategy = new KvsRetryStrategy(config);

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
    300000L, // metricLoggingPeriod - log metrics every 5 minutes
    0L,      // reservedCallbackPeriod - not used yet
    retryStrategy
);
```

## 🔄 How It Works

```
Java Configuration → JNI Bridge → PIC Native Config → Automatic Retries
                                        ↓
                              Your custom settings control:
                              • Retry count limits
                              • Exponential backoff timing
                              • Jitter algorithms
                              • State reset behavior
```

1. **You Configure**: Create `ExponentialBackoffRetryStrategyConfig` with your settings
2. **JNI Converts**: Converts Java config to native PIC struct
3. **PIC Uses**: PIC's exponential backoff algorithm uses your custom parameters
4. **Zero Maintenance**: No callbacks to implement or maintain

## 🧪 Testing

```bash
# Run tests
mvn test -Dtest=ExponentialBackoffRetryStrategyConfigTest
mvn test -Dtest=KvsRetryStrategyTest

# Run example
java -cp target/classes com.amazonaws.kinesisvideo.demoapp.RetryStrategyExample
```

## 🔄 Migration from Previous Versions

### Before (Old ClientInfo)
```java
ClientInfo clientInfo = new ClientInfo(
    /* 9 parameters */
);
```

### After (New ClientInfo)
```java
// Option 1: Use defaults
KvsRetryStrategy retryStrategy = new KvsRetryStrategy();

// Option 2: Custom configuration
ExponentialBackoffRetryStrategyConfig config = new ExponentialBackoffRetryStrategyConfig(
    3, 5000, 1000); // maxRetries, maxWaitMs, factorMs
KvsRetryStrategy retryStrategy = new KvsRetryStrategy(config);

ClientInfo clientInfo = new ClientInfo(
    /* same 9 parameters */,
    0L,           // metricLoggingPeriod (NEW)
    0L,           // reservedCallbackPeriod (NEW)
    retryStrategy // kvsRetryStrategy (NEW)
);
```

## ✅ Benefits

### Before Retry Strategy
```
❌ Network timeout → Operation fails → Manual intervention
❌ Service unavailable → Stream stops → Application restart
❌ No control over retry behavior → One-size-fits-all approach
```

### After Retry Strategy
```
✅ Network timeout → Custom retry logic → Configurable recovery
✅ Service unavailable → Your exponential backoff → Seamless recovery
✅ Full control → Fine-tuned for your use case → Optimal performance
```

## 🎯 Production Examples

### Conservative Configuration (Low Retry Overhead)
```java
ExponentialBackoffRetryStrategyConfig conservative = new ExponentialBackoffRetryStrategyConfig(
    2,      // Only 2 retry attempts
    5000,   // Max 5 second wait
    2000    // Start with 2 second delay
);
```

### Aggressive Configuration (High Reliability)
```java
ExponentialBackoffRetryStrategyConfig aggressive = new ExponentialBackoffRetryStrategyConfig(
    10,     // Up to 10 retry attempts
    30000,  // Max 30 second wait
    500,    // Start with 500ms delay
    300000, // Reset after 5 minutes
    ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER,
    0
);
```

### Balanced Configuration (Recommended)
```java
ExponentialBackoffRetryStrategyConfig balanced = new ExponentialBackoffRetryStrategyConfig(
    5,      // 5 retry attempts
    16000,  // Max 16 second wait (PIC default)
    1000,   // 1 second base delay (PIC default)
    90000,  // 90 second reset (PIC default)
    ExponentialBackoffRetryStrategyConfig.JitterType.FULL_JITTER,
    0
);
```

## 🎉 Why This Approach?

- **Powerful**: Full control over PIC's proven exponential backoff algorithm
- **Simple**: Easy-to-use Java configuration classes
- **Reliable**: Uses PIC's battle-tested retry implementation
- **Performant**: No JNI callback overhead during retries
- **Flexible**: From simple defaults to fine-grained control
- **Maintainable**: Clear configuration, no complex callback logic

## 📞 Support

1. 🧪 Run the provided examples and tests
2. 📖 Check the comprehensive documentation in this directory
3. 🐛 Enable debug logging for troubleshooting
4. 💬 Refer to the main project README for community support

---

**Happy Streaming! 🎥✨**

*The retry strategy feature gives you full control over retry behavior while leveraging PIC's proven algorithms!*
