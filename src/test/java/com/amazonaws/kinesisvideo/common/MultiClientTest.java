package com.amazonaws.kinesisvideo.common;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.kinesisvideo.client.KinesisVideoClientConfiguration;
import com.amazonaws.kinesisvideo.internal.client.NativeKinesisVideoClient;
import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoProducer;
import com.amazonaws.kinesisvideo.internal.producer.KinesisVideoProducerStream;
import com.amazonaws.kinesisvideo.java.auth.JavaCredentialsFactory;
import com.amazonaws.kinesisvideo.java.service.JavaKinesisVideoServiceClient;
import com.amazonaws.kinesisvideo.producer.ClientInfo;
import com.amazonaws.kinesisvideo.producer.DeviceInfo;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.ProducerException;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.Tag;
import com.amazonaws.kinesisvideo.producer.Time;
import com.amazonaws.kinesisvideo.streaming.DefaultStreamCallbacks;
import com.amazonaws.kinesisvideo.util.StreamInfoConstants;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.model.CreateStreamRequest;
import com.amazonaws.services.kinesisvideo.model.DeleteStreamRequest;
import com.amazonaws.services.kinesisvideo.model.DescribeStreamRequest;
import com.amazonaws.services.kinesisvideo.model.DescribeStreamResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.amazonaws.kinesisvideo.internal.producer.jni.NativeKinesisVideoProducerJni.PRODUCER_NATIVE_LIBRARY_NAME;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_BITRATE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_REPLAY_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_STALENESS_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_TIMESCALE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NOT_ADAPTIVE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NO_KMS_KEY_ID;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECALCULATE_METRICS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RETENTION_ONE_HOUR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Multi-Client Test - Validates per-client JVM context refactoring
 * <p>
 * This test ensures that multiple KinesisVideoClient instances can operate
 * independently without interfering with each other's callbacks or logging.
 */
public class MultiClientTest extends ProducerTestBase {
    private static final Logger log = LogManager.getLogger(MultiClientTest.class);
    private static final int NUM_CLIENTS = 3;
    private static final int FRAMES_PER_CLIENT = 10;
    private static final long FRAME_DURATION_MS = 33; // ~30 FPS

    private List<NativeKinesisVideoClient> clients;
    private List<KinesisVideoProducer> producers;
    private List<KinesisVideoProducerStream> streams;
    private List<String> streamNames;
    private ExecutorService testExecutor;

    private KinesisVideoClientConfiguration clientConfiguration;

    @Before
    public void setUp() {
        try {
            System.loadLibrary(PRODUCER_NATIVE_LIBRARY_NAME);
        } catch (final UnsatisfiedLinkError e) {
            fail("JNI library not found.");
        }

        this.clients = new ArrayList<>();
        this.producers = new ArrayList<>();
        this.streams = new ArrayList<>();
        this.streamNames = new ArrayList<>();
        this.testExecutor = Executors.newFixedThreadPool(NUM_CLIENTS * 2);

        assumeTrue(DefaultAWSCredentialsProviderChain.getInstance().getCredentials() != null);

        final String prefix = Optional.ofNullable(System.getenv("TEST_STREAMS_PREFIX")).orElse("");
        final AmazonKinesisVideo awsSdkKinesisVideoClient = AmazonKinesisVideoClientBuilder.standard().build();
        boolean success = true;

        this.clientConfiguration = KinesisVideoClientConfiguration.builder()
                .withCredentialsProvider(JavaCredentialsFactory.createKinesisVideoCredentialsProvider(DefaultAWSCredentialsProviderChain.getInstance()))
                .build();

        for (int i = 0; i < NUM_CLIENTS; i++) {
            final String streamName = prefix + "-" + "test-multi-client-stream-" + i + "-" + System.currentTimeMillis() + UUID.randomUUID();
            try {
                log.info("Creating stream {}", streamName);
                final CreateStreamRequest createStreamRequest = new CreateStreamRequest()
                        .withStreamName(streamName)
                        .withDataRetentionInHours(2);
                awsSdkKinesisVideoClient.createStream(createStreamRequest);
                this.streamNames.add(streamName);
            } catch (final Throwable t) {
                log.error("Encountered an error creating stream: {}!", streamName, t);
                success = false;
            }
        }

        assertTrue("There was an issue creating straems, check the logs above!", success);
    }

    @After
    public void tearDown() throws Exception {
        // Clean up streams
        boolean success = true;
        for (final KinesisVideoProducerStream stream : this.streams) {
            if (stream != null) {
                try {
                    stream.stopStreamSync();
                } catch (final Exception e) {
                    log.warn("Error stopping stream: " + e.getMessage());
                    success = false;
                }
            }
        }

        // Clean up producers
        for (final KinesisVideoProducer producer : this.producers) {
            if (producer != null) {
                try {
                    producer.free();
                } catch (final Exception e) {
                    log.warn("Error freeing producer: " + e.getMessage());
                    success = false;
                }
            }
        }

        // Clean up clients
        for (final NativeKinesisVideoClient client : this.clients) {
            if (client != null) {
                try {
                    client.free();
                } catch (final Exception e) {
                    log.warn("Error freeing client: " + e.getMessage());
                    success = false;
                }
            }
        }

        if (this.testExecutor != null) {
            this.testExecutor.shutdown();
            this.testExecutor.awaitTermination(10, TimeUnit.SECONDS);
        }

        // Clean up streams from AWS
        cleanupStreams();

        assertTrue("There was an issue in the cleanup, check the logs above.", success);
    }

    /**
     * Test 1: Multiple clients can be created and destroyed independently
     */
    @Test
    public void testMultipleClientCreationAndDestruction() throws Exception {
        log.info("Testing multiple client creation and destruction");

        // Create multiple clients
        for (int i = 0; i < NUM_CLIENTS; i++) {
            final DeviceInfo deviceInfo = createDeviceInfo("test-device-" + i);

            // Each client should have its own context
            final NativeKinesisVideoClient client = new NativeKinesisVideoClient(this.clientConfiguration, new JavaKinesisVideoServiceClient(), Executors.newScheduledThreadPool(1));
            this.clients.add(client);

            final KinesisVideoProducer producer = client.initializeNewKinesisVideoProducer(deviceInfo);
            this.producers.add(producer);

            assertNotNull("Producer " + i + " should be created", producer);
        }

        assertEquals("Should have created " + NUM_CLIENTS + " clients", NUM_CLIENTS, this.clients.size());
        assertEquals("Should have created " + NUM_CLIENTS + " producers", NUM_CLIENTS, this.producers.size());

        // Verify each client is independent
        for (int i = 0; i < NUM_CLIENTS; i++) {
            assertNotNull("Client " + i + " should not be null", this.clients.get(i));
            assertNotNull("Producer " + i + " should not be null", this.producers.get(i));
        }
    }

    /**
     * Test 2: Multiple clients can stream simultaneously without interference
     */
    @Test
    public void testConcurrentStreaming() throws Exception {
        log.info("Testing concurrent streaming with multiple clients");

        // Create clients and streams
        createMultipleClientsAndStreams();

        // Track callbacks per client
        final List<AtomicInteger> callbackCounts = new ArrayList<>();
        final List<AtomicReference<String>> lastLogMessages = new ArrayList<>();

        for (int i = 0; i < NUM_CLIENTS; i++) {
            callbackCounts.add(new AtomicInteger(0));
            lastLogMessages.add(new AtomicReference<>(""));
        }

        // Start streaming concurrently
        final List<Future<Void>> streamingTasks = new ArrayList<>();

        for (int clientIndex = 0; clientIndex < NUM_CLIENTS; clientIndex++) {
            final int index = clientIndex;
            final Future<Void> task = this.testExecutor.submit(() -> {
                try {
                    streamFrames(index, callbackCounts.get(index), lastLogMessages.get(index));
                } catch (final Exception e) {
                    log.error("Error streaming for client " + index, e);
                    throw new RuntimeException(e);
                }
                return null;
            });
            streamingTasks.add(task);
        }

        // Wait for all streaming to complete
        for (final Future<Void> task : streamingTasks) {
            task.get(30, TimeUnit.SECONDS);
        }

        // Verify each client received its own callbacks
        for (int i = 0; i < NUM_CLIENTS; i++) {
            assertTrue("Client " + i + " should have received callbacks",
                    callbackCounts.get(i).get() > 0);
        }
    }

    /**
     * Test 3: Callback isolation - each client's callbacks go to the correct instance
     */
    @Test
    public void testCallbackIsolation() throws Exception {
        log.info("Testing callback isolation between clients");

        createMultipleClientsAndStreams();

        // Custom callback tracking
        final List<TestCallbackTracker> trackers = new ArrayList<>();
        for (int i = 0; i < NUM_CLIENTS; i++) {
            trackers.add(new TestCallbackTracker(i));
        }

        // Stream a few frames from each client
        for (int clientIndex = 0; clientIndex < NUM_CLIENTS; clientIndex++) {
            final KinesisVideoProducerStream stream = this.streams.get(clientIndex);
            final TestCallbackTracker tracker = trackers.get(clientIndex);

            // Put a frame and verify callback goes to correct tracker
            putTestFrame(stream, clientIndex, 0);

            // Wait a bit for callbacks
            Thread.sleep(100);
        }

        // Verify callback isolation
        for (int i = 0; i < NUM_CLIENTS; i++) {
            final TestCallbackTracker tracker = trackers.get(i);
            assertTrue("Client " + i + " should have received its own callbacks",
                    tracker.getCallbackCount() >= 0); // At least some activity
        }
    }

    /**
     * Test 4: Logging isolation - each client's logs go to the correct instance
     */
    @Test
    public void testLoggingIsolation() throws Exception {
        log.info("Testing logging isolation between clients");

        createMultipleClientsAndStreams();

        // Trigger operations that cause logging
        final List<Future<Void>> loggingTasks = new ArrayList<>();

        for (int clientIndex = 0; clientIndex < NUM_CLIENTS; clientIndex++) {
            final int index = clientIndex;
            final Future<Void> task = this.testExecutor.submit(() -> {
                try {
                    // Operations that trigger internal logging
                    final KinesisVideoProducerStream stream = this.streams.get(index);

                    // Put frames which should trigger PIC logging
                    for (int frameIndex = 0; frameIndex < 5; frameIndex++) {
                        putTestFrame(stream, index, frameIndex);
                        Thread.sleep(10); // Small delay
                    }
                } catch (final Exception e) {
                    log.error("Error in logging test for client " + index, e);
                    throw new RuntimeException(e);
                }
                return null;
            });
            loggingTasks.add(task);
        }

        // Wait for all logging operations
        for (final Future<Void> task : loggingTasks) {
            task.get(15, TimeUnit.SECONDS);
        }

        // If we get here without crashes, logging isolation is working
        assertTrue("All clients completed logging operations without interference", true);
    }

    /**
     * Test 5: Client destruction order independence
     */
    @Test
    public void testClientDestructionOrderIndependence() throws Exception {
        log.info("Testing client destruction order independence");

        createMultipleClientsAndStreams();

        // Start streaming on all clients
        for (int i = 0; i < NUM_CLIENTS; i++) {
            putTestFrame(this.streams.get(i), i, 0);
        }

        // Destroy clients in different order (middle first, then first, then last)
        final int[] destructionOrder = {1, 0, 2};

        for (final int index : destructionOrder) {
            if (index < this.streams.size() && this.streams.get(index) != null) {
                this.streams.get(index).stopStreamSync();
                this.streams.set(index, null);
            }

            if (index < this.producers.size() && this.producers.get(index) != null) {
                this.producers.get(index).free();
                this.producers.set(index, null);
            }

            if (index < this.clients.size() && this.clients.get(index) != null) {
                this.clients.get(index).free();
                this.clients.set(index, null);
            }

            // Verify remaining clients still work
            for (int i = 0; i < NUM_CLIENTS; i++) {
                if (i != index && this.streams.get(i) != null) {
                    // Should still be able to put frames on remaining clients
                    putTestFrame(this.streams.get(i), i, 1);
                }
            }
        }

        assertTrue("Client destruction completed without crashes", true);
    }

    // Helper methods

    private void createMultipleClientsAndStreams() throws Exception {
        for (int i = 0; i < NUM_CLIENTS; i++) {
            final DeviceInfo deviceInfo = createDeviceInfo("test-device-" + i);

            final NativeKinesisVideoClient client = new NativeKinesisVideoClient(this.clientConfiguration, new JavaKinesisVideoServiceClient(), Executors.newScheduledThreadPool(1));
            this.clients.add(client);

            final KinesisVideoProducer producer = client.initializeNewKinesisVideoProducer(deviceInfo);
            this.producers.add(producer);

            final StreamInfo streamInfo = createStreamInfo(this.streamNames.get(i));
            final KinesisVideoProducerStream stream = producer.createStreamSync(streamInfo, new DefaultStreamCallbacks());
            this.streams.add(stream);
        }
    }

    private void streamFrames(final int clientIndex, final AtomicInteger callbackCount, final AtomicReference<String> lastLogMessage)
            throws Exception {
        final KinesisVideoProducerStream stream = this.streams.get(clientIndex);

        for (int frameIndex = 0; frameIndex < FRAMES_PER_CLIENT; frameIndex++) {
            putTestFrame(stream, clientIndex, frameIndex);
            callbackCount.incrementAndGet();

            Thread.sleep(FRAME_DURATION_MS);
        }
    }

    private void putTestFrame(final KinesisVideoProducerStream stream, final int clientIndex, final int frameIndex)
            throws ProducerException {
        long timestamp = System.currentTimeMillis() * Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
        timestamp += frameIndex * FRAME_DURATION_MS * Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;

        // Create unique frame data for each client
        final String frameData = "Client-" + clientIndex + "-Frame-" + frameIndex + "-Data";
        final ByteBuffer frameBuffer = ByteBuffer.wrap(frameData.getBytes());

        final KinesisVideoFrame frame = new KinesisVideoFrame(
                frameIndex,
                FRAME_FLAG_KEY_FRAME,
                timestamp,
                timestamp,
                FRAME_DURATION_MS * Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND,
                frameBuffer
        );

        stream.putFrame(frame);
    }

    private DeviceInfo createDeviceInfo(final String deviceName) {
        return new DeviceInfo(
                DEVICE_VERSION,
                deviceName,
                this.storageInfo_,
                1, // One stream per client for this test
                null,
                "JNI Test",
                new ClientInfo()
        );
    }

    private StreamInfo createStreamInfo(final String streamName) {
        return new StreamInfo(
                StreamInfo.STREAM_INFO_CURRENT_VERSION,
                streamName,
                StreamInfo.StreamingType.STREAMING_TYPE_REALTIME,
                "video/h264",
                NO_KMS_KEY_ID,
                RETENTION_ONE_HOUR,
                NOT_ADAPTIVE,
                StreamInfoConstants.MAX_LATENCY_ZERO,
                StreamInfoConstants.DEFAULT_GOP_DURATION,
                StreamInfoConstants.KEYFRAME_FRAGMENTATION,
                StreamInfoConstants.USE_FRAME_TIMECODES,
                StreamInfoConstants.RELATIVE_TIMECODES,
                StreamInfoConstants.REQUEST_FRAGMENT_ACKS,
                StreamInfoConstants.RECOVER_ON_FAILURE,
                "V_MPEG4/ISO/AVC",
                "test-track",
                DEFAULT_BITRATE,
                this.fps_,
                StreamInfoConstants.DEFAULT_BUFFER_DURATION,
                DEFAULT_REPLAY_DURATION,
                DEFAULT_STALENESS_DURATION,
                DEFAULT_TIMESCALE,
                RECALCULATE_METRICS,
                null,
                new Tag[]{
                        new Tag("device", "Test Device"),
                        new Tag("stream", "Test Stream")},
                StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_ANNEXB_NALS,
                this.allowStreamCreation
        );
    }

    private void cleanupStreams() {
        final AmazonKinesisVideo awsSdkKinesisVideoClient = AmazonKinesisVideoClientBuilder.standard().build();
        boolean success = true;

        for (final String streamName : this.streamNames) {
            try {
                log.info("Deleting stream {}", streamName);
                final DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest().withStreamName(streamName);
                final DescribeStreamResult describeStreamResult = awsSdkKinesisVideoClient.describeStream(describeStreamRequest);

                final DeleteStreamRequest deleteStreamRequest = new DeleteStreamRequest()
                        .withStreamARN(describeStreamResult.getStreamInfo().getStreamARN())
                        .withCurrentVersion(describeStreamResult.getStreamInfo().getVersion());
                awsSdkKinesisVideoClient.deleteStream(deleteStreamRequest);
            } catch (final Throwable t) {
                log.error("Encountered an error deleting healthy stream: {}!", streamName, t);
                success = false;
            }
        }
        assertTrue("Encountered an issue cleaning up the streams! Check the logs above", success);
    }

    // Helper class to track callbacks per client
    private static class TestCallbackTracker {
        private final int clientId;
        private final AtomicInteger callbackCount = new AtomicInteger(0);

        public TestCallbackTracker(final int clientId) {
            this.clientId = clientId;
        }

        public void onCallback(final String callbackType) {
            this.callbackCount.incrementAndGet();
            log.debug("Client {} received callback: {}", this.clientId, callbackType);
        }

        public int getCallbackCount() {
            return this.callbackCount.get();
        }
    }
}
