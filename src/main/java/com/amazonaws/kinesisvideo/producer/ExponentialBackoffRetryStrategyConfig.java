package com.amazonaws.kinesisvideo.producer;

/**
 * Configuration for exponential backoff retry strategy.
 * This maps to the native ExponentialBackoffRetryStrategyConfig struct in PIC.
 * <p>
 * Use 0 for any parameter to let PIC use its built-in default values.
 * </p>
 */
public class ExponentialBackoffRetryStrategyConfig {

    /**
     * Jitter types that correspond to the native ExponentialBackoffJitterType enum
     */
    public enum JitterType {
        FULL_JITTER(0x01),
        FIXED_JITTER(0x02),
        NO_JITTER(0x03);

        private final int value;

        JitterType(final int value) {
            this.value = value;
        }

        public int getValue() {
            return this.value;
        }

        public static JitterType fromValue(final int value) {
            for (final JitterType type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown jitter type: " + value);
        }
    }

    // Sentinel value - 0 means "use PIC defaults"
    public static final long USE_PIC_DEFAULT = 0;

    /**
     * Max retries after which an error will be returned to the application.
     * For infinite retries, set this to KVS_INFINITE_EXPONENTIAL_RETRIES (0).
     */
    private final long maxRetryCount;

    /**
     * Maximum retry wait time. Once the retry wait time reaches this value,
     * subsequent retries will wait for maxRetryWaitTime (plus jitter).
     */
    private final long maxRetryWaitTimeMs;

    /**
     * Factor for computing the exponential backoff wait time
     */
    private final long retryFactorTimeMs;

    /**
     * The minimum time between two consecutive retries after which retry state will be reset i.e. retries
     * will start from initial retry state.
     */
    private final long minTimeToResetRetryStateMs;

    /**
     * Jitter type indicating how much jitter to be added
     * Default will be {@link JitterType#FULL_JITTER}
     */
    private final JitterType jitterType;

    /**
     * Factor determining random jitter value.
     * Jitter will be between {@code [0, jitterFactor)}.
     * This parameter is only valid for jitter type {@link JitterType#FIXED_JITTER}
     */
    private final long jitterFactor;

    /**
     * Constructor with all PIC defaults (recommended)
     */
    public ExponentialBackoffRetryStrategyConfig() {
        this(USE_PIC_DEFAULT, USE_PIC_DEFAULT, USE_PIC_DEFAULT, USE_PIC_DEFAULT, null, USE_PIC_DEFAULT);
    }

    /**
     * Constructor with custom values. Use {@link #USE_PIC_DEFAULT} or {@code null} for PIC default.
     *
     * @param maxRetryCount              Maximum number of retries
     * @param maxRetryWaitTimeMs         Maximum wait time between retries in milliseconds
     * @param retryFactorTimeMs          Base factor for exponential backoff calculation in milliseconds
     * @param minTimeToResetRetryStateMs Minimum time to reset retry state in milliseconds
     * @param jitterType                 Type of jitter to apply
     * @param jitterFactor               Jitter factor in milliseconds, only used for {@link JitterType#FIXED_JITTER}
     */
    public ExponentialBackoffRetryStrategyConfig(final long maxRetryCount, final long maxRetryWaitTimeMs,
                                                 final long retryFactorTimeMs, final long minTimeToResetRetryStateMs,
                                                 final JitterType jitterType, final long jitterFactor) {
        this.maxRetryCount = maxRetryCount;
        this.maxRetryWaitTimeMs = maxRetryWaitTimeMs;
        this.retryFactorTimeMs = retryFactorTimeMs;
        this.minTimeToResetRetryStateMs = minTimeToResetRetryStateMs;
        this.jitterType = jitterType;
        this.jitterFactor = jitterFactor;
    }

    public long getMaxRetryCount() {
        return this.maxRetryCount;
    }

    public long getMaxRetryWaitTimeMs() {
        return this.maxRetryWaitTimeMs;
    }

    public long getRetryFactorTimeMs() {
        return this.retryFactorTimeMs;
    }

    public long getMinTimeToResetRetryStateMs() {
        return this.minTimeToResetRetryStateMs;
    }

    public JitterType getJitterType() {
        return this.jitterType;
    }

    public int getJitterTypeValue() {
        return this.jitterType != null ? this.jitterType.getValue() : (int) USE_PIC_DEFAULT;
    }

    public long getJitterFactor() {
        return this.jitterFactor;
    }

    @Override
    public String toString() {
        return "ExponentialBackoffRetryStrategyConfig{" +
                "maxRetryCount=" + (this.maxRetryCount == USE_PIC_DEFAULT ? "PIC_DEFAULT" : this.maxRetryCount) +
                ", maxRetryWaitTimeMs=" + (this.maxRetryWaitTimeMs == USE_PIC_DEFAULT ? "PIC_DEFAULT" : this.maxRetryWaitTimeMs) +
                ", retryFactorTimeMs=" + (this.retryFactorTimeMs == USE_PIC_DEFAULT ? "PIC_DEFAULT" : this.retryFactorTimeMs) +
                ", minTimeToResetRetryStateMs=" + (this.minTimeToResetRetryStateMs == USE_PIC_DEFAULT ? "PIC_DEFAULT" : this.minTimeToResetRetryStateMs) +
                ", jitterType=" + (this.jitterType == null ? "PIC_DEFAULT" : this.jitterType) +
                ", jitterFactor=" + (this.jitterFactor == USE_PIC_DEFAULT ? "PIC_DEFAULT" : this.jitterFactor) +
                '}';
    }
}
