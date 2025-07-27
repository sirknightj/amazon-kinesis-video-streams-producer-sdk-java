package com.amazonaws.kinesisvideo.common;

import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoProducerStream;
import com.amazonaws.kinesisvideo.producer.ClientInfo;
import com.amazonaws.kinesisvideo.producer.DeviceInfo;
import com.amazonaws.kinesisvideo.producer.ExponentialBackoffRetryStrategyConfig;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.KvsRetryStrategy;
import com.amazonaws.kinesisvideo.producer.ProducerException;
import com.amazonaws.kinesisvideo.producer.StorageInfo;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.Tag;
import com.amazonaws.kinesisvideo.util.LogCaptureRule;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.nio.ByteBuffer;
import java.util.List;

import static com.amazonaws.kinesisvideo.producer.ProducerException.STATUS_OPERATION_TIMED_OUT;
import static com.amazonaws.kinesisvideo.producer.ProducerException.STATUS_SUCCESS;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * These tests check the struct migration/versioning pattern used in PIC.
 * <ul>
 *     <li>{@link DeviceInfo} is the main parameter object for configuring the KVS Producer client.</li>
 *     <li>The v0 of the struct has a few members</li>
 *     <li>The v1 of the struct has a couple new members, in particular, the {@link ClientInfo} parameter,
 *     which also has multiple struct versions on its own</li>
 * </ul>
 *
 * @see <a href="https://github.com/awslabs/amazon-kinesis-video-streams-pic">Amazon Kinesis Video Streams PIC</a>
 */
public class RetryStrategyTest extends ProducerTestBase {

    private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(RetryStrategyTest.class);

    private static final int STORAGE_INFO_VERSION_ZERO = 0;
    private static final int ONE_SECOND_HUNDREDS_OF_NANOS = 1000 * 10000;
    private static final int TEN_SECONDS_HUNDREDS_OF_NANOS = 10 * ONE_SECOND_HUNDREDS_OF_NANOS;

    private static final long STATUS_EXPONENTIAL_BACKOFF_RETRIES_EXHAUSTED = 0x4000002B;
    private static final long STATUS_DESCRIBE_STREAM_CALL_FAILED = 0x52000011L;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(30);

    @Rule
    public LogCaptureRule logCapture = new LogCaptureRule();

    private StorageInfo storageInfo;

    @Before
    public void setUp() {
        final boolean jniLoaded = isJNILoaded();
        if (!jniLoaded) {
            fail("JNI library not found.");
        }

        final long storageSizeBytes = 10 * 1024 * 1024; // 10 MiB
        final int spillRatioPercent = 90;
        final String rootDirectory = "/tmp";

        this.storageInfo = new StorageInfo(STORAGE_INFO_VERSION_ZERO,
                StorageInfo.DeviceStorageType.DEVICE_STORAGE_TYPE_IN_MEM, storageSizeBytes,
                spillRatioPercent, rootDirectory);
    }

    @After
    public void tearDown() {
        // LogCaptureRule handles cleanup automatically
    }


    @SuppressWarnings({"UnnecessaryLocalVariable", "ConstantConditions"})
    private ClientInfo createClientInfoV2WithRetryStrategy(final KvsRetryStrategy retryStrategy) {
        // ClientInfo V0 fields
        final long createClientTimeout = ONE_SECOND_HUNDREDS_OF_NANOS;
        final long createStreamTimeout = TEN_SECONDS_HUNDREDS_OF_NANOS;
        final long stopStreamTimeout = ONE_SECOND_HUNDREDS_OF_NANOS;
        final long offlineBufferAvailabilityTimeout = ONE_SECOND_HUNDREDS_OF_NANOS;
        final int logLevel = ClientInfo.LOG_LEVEL_DEBUG;

        // ClientInfo V1 fields
        final boolean doLogMetrics = false;

        // ClientInfo V2 fields
        final long metricsLoggingPeriod = ONE_SECOND_HUNDREDS_OF_NANOS;
        final ClientInfo.AutomaticStreamingFlags automaticStreamingFlags = null;
        final long reservedCallbackPeriod = ONE_SECOND_HUNDREDS_OF_NANOS;
        final KvsRetryStrategy kvsRetryStrategy = retryStrategy;

        return ClientInfo.createClientInfoV2(createClientTimeout,
                createStreamTimeout, stopStreamTimeout, offlineBufferAvailabilityTimeout,
                logLevel, doLogMetrics, metricsLoggingPeriod, automaticStreamingFlags,
                reservedCallbackPeriod, kvsRetryStrategy);
    }

    @SuppressWarnings("ConstantConditions")
    private DeviceInfo createDeviceInfoV1(final ClientInfo clientInfo) {
        final String deviceName = "java-test-application";
        final int streamCount = 10;
        final Tag[] tags = null;
        final String clientId = String.format("ProducerJava-%s-%s", this.getClass().getSimpleName(),
                new Object() {
                }.getClass().getEnclosingMethod().getName());

        return DeviceInfo.createDeviceInfoV1(deviceName, this.storageInfo, streamCount, tags,
                clientId, clientInfo);
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void givenNullRetryStrategy_whenCreatingProducer_thenStreamingSucceeds() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = null;

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void givenDefaultRetryStrategy_whenCreatingProducer_thenStreamingSucceeds() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy();

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void givenDisabledRetryStrategy_whenCreatingProducer_thenStreamingSucceeds() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(KvsRetryStrategy.RetryStrategyType.DISABLED);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void givenExponentialBackoffRetryStrategy_whenCreatingProducer_thenStreamingSucceeds() throws ProducerException {

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void givenRetryStrategyWithPicDefaults_whenCreatingProducer_thenStreamingSucceeds() throws ProducerException {

        final ExponentialBackoffRetryStrategyConfig exponentialBackoffStrategyConfig =
                new ExponentialBackoffRetryStrategyConfig();

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(exponentialBackoffStrategyConfig);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    @Test
    @SuppressWarnings({"ConstantConditions", "ExtractMethodRecommender"})
    public void givenCustomConfiguredRetryStrategy_whenCreatingProducer_thenStreamingSucceeds() throws ProducerException {

        final long maxRetryCount = 100;
        final long maxRetryWaitTimeMs = 20000;
        final long retryFactorTimeMs = 1000;
        final long minTimeToResetRetryStateMs = 100005;
        final ExponentialBackoffRetryStrategyConfig.JitterType jitterType = ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER;
        final long jitterFactor = 100;

        final ExponentialBackoffRetryStrategyConfig exponentialBackoffStrategyConfig =
                new ExponentialBackoffRetryStrategyConfig(maxRetryCount, maxRetryWaitTimeMs, retryFactorTimeMs,
                        minTimeToResetRetryStateMs, jitterType, jitterFactor);

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(KvsRetryStrategy.RetryStrategyType.EXPONENTIAL_BACKOFF_WAIT, exponentialBackoffStrategyConfig);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        streamNormally(methodName);

        free();

    }

    /**
     * This test validates that the custom configuration is actually used by the native code.
     * <p>
     *     Note: The maxRetryCount currently tells PIC when to reset the internal retry count for the backoff
     *     calculations. If the state machine has infinite retries configured for this state, the calculated
     *     times will be in a wave pattern with a period of maxRetryCount.
     * </p>
     */
    @Test
    @SuppressWarnings({"ConstantConditions", "ExtractMethodRecommender"})
    public void givenLowMaxRetryCount_whenStreamCreationFails_thenRetryExhaustionIsLogged() throws ProducerException {

        final long maxRetryCount = 1;
        final long maxRetryWaitTimeMs = 10000;
        final long retryFactorTimeMs = 50;
        final long minTimeToResetRetryStateMs = 90000;
        final ExponentialBackoffRetryStrategyConfig.JitterType jitterType = ExponentialBackoffRetryStrategyConfig.JitterType.NO_JITTER;
        final long jitterFactor = 50;

        final ExponentialBackoffRetryStrategyConfig exponentialBackoffStrategyConfig =
                new ExponentialBackoffRetryStrategyConfig(maxRetryCount, maxRetryWaitTimeMs, retryFactorTimeMs,
                        minTimeToResetRetryStateMs, jitterType, jitterFactor);

        final KvsRetryStrategy kvsRetryStrategy = new KvsRetryStrategy(exponentialBackoffStrategyConfig);

        final ClientInfo clientInfo = createClientInfoV2WithRetryStrategy(kvsRetryStrategy);
        final DeviceInfo deviceInfo = createDeviceInfoV1(clientInfo);

        createProducer(deviceInfo);

        final String methodName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        final String badCharacter = ","; // Using this to throw a 400 due to invalid character
        final String streamName = "DeviceInfoClientInfoVersionTest-" + methodName + "-" + System.currentTimeMillis() + "-" + badCharacter;
        streamExpectCreateFailure(streamName);

        free();

        // Since we're using max retries = 1, we should see this message. The describeStream state has a retryCount of 5.
        final List<String> errorMessages = this.logCapture.getLogMessagesAtLevel(Level.ERROR);
        final boolean containsMaxRetriesMessage = errorMessages.stream()
                .anyMatch(message -> message.toLowerCase().contains(String.format("0x%08x", STATUS_EXPONENTIAL_BACKOFF_RETRIES_EXHAUSTED)));
        assertTrue("Did not find max retries in the logs", containsMaxRetriesMessage);

        final boolean containsDescribeFailure = errorMessages.stream()
                .anyMatch(message -> message.toLowerCase().contains(String.format("0x%08x", STATUS_DESCRIBE_STREAM_CALL_FAILED)));
        assertTrue("Did not receive a describe stream failure in the logs", containsDescribeFailure);
    }

    /**
     * Performs stream creation, putFrame, error verification, and resource cleanup.
     *
     * @param methodName Name of the caller method. A procedurally-generated stream name that includes the method name
     *                   will be used.
     */
    private void streamNormally(final String methodName) {
        try {
            testStreaming(methodName, false);
        } catch (final ProducerException e) {
            log.error("Encountered an error while streaming!", e);
            fail(e.getMessage());
        }

        // frameDropped_ is set to false initially. It can be set to true by droppedFrameReport callback in case there
        // was a frame that was dropped during the test
        assertFalse(this.frameDropped_);
        // errorStatus_ is set to STATUS_SUCCESS initially. It can be set to a different statusCode by
        // streamErrorReport callback in case an error is encountered during the test
        assertEquals(STATUS_SUCCESS, this.errorStatus_);
        // bufferingAckInSequence_ is true initially. It can be set to false by fragmentAckReceived callback in case the
        // (current timestamp - previous timestamp of the ack) > fragment duration
        assertTrue(this.bufferingAckInSequence_);
    }

    private void streamExpectCreateFailure(final String methodName) {
        try {
            testStreaming(methodName, true);
            fail(methodName + " should have thrown an exception");
        } catch (final ProducerException e) {
            assertEquals("Creating the stream should have timed out!",
                    STATUS_OPERATION_TIMED_OUT, e.getStatusCode());
        }

        // Since we're not streaming anything, we're not expecting any error codes
        // errorStatus_ is set to STATUS_SUCCESS initially. It can be set to a different statusCode by
        // streamErrorReport callback in case an error is encountered during the test
        assertEquals(STATUS_SUCCESS, this.errorStatus_);
    }

    @SuppressWarnings({"UnnecessaryLocalVariable"})
    private void testStreaming(final String methodName, final boolean skipPreparation) throws ProducerException {
        final String streamName = "DeviceInfoClientInfoVersionTest-" + methodName + "-" + System.currentTimeMillis();
        final StreamInfo.StreamingType streamingType = StreamInfo.StreamingType.STREAMING_TYPE_REALTIME;
        final long maxLatency = TEN_SECONDS_HUNDREDS_OF_NANOS;
        final long bufferDuration = TEN_SECONDS_HUNDREDS_OF_NANOS;

        final KinesisVideoProducerStream stream = createTestStream(streamName, streamingType, maxLatency,
                bufferDuration, StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE, skipPreparation);

        final long now = System.currentTimeMillis();
        final int fps = 10;
        final int durationSec = 3;
        final int keyFrameIntervalSec = 1;

        // Stream durationSec seconds ago until now
        for (int index = 0; index < durationSec * fps; index++) {
            final int flags = index % (keyFrameIntervalSec * fps) == 0 ? FRAME_FLAG_KEY_FRAME : FRAME_FLAG_NONE;
            final long frameDurationMs = 1000 / fps;
            final long timestampMs = now - (durationSec * 1000) + index * frameDurationMs;
            final long dtsPtsHundredsOfNanos = timestampMs * HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
            final byte[] mockData = new byte[]{1, 2, 3, 4};
            final KinesisVideoFrame testFrame = new KinesisVideoFrame(index, flags,
                    dtsPtsHundredsOfNanos, dtsPtsHundredsOfNanos,
                    1000 / fps * HUNDREDS_OF_NANOS_IN_A_MILLISECOND, ByteBuffer.wrap(mockData));
            try {
                stream.putFrame(testFrame);

                Thread.sleep(frameDurationMs);
            } catch (final Exception e) {
                log.error("Encountered an error while streaming!", e);
                fail("Failed to put the frames into the stream! " + e.getMessage());
            }
        }
        try {
            Thread.sleep(WAIT_5_SECONDS_FOR_ACKS);
        } catch (final InterruptedException e) {
            log.error("Interrupted while waiting for the acks!", e);
            fail();
        }

        freeTestStream(stream);

        deleteStream(streamName);
    }

}
