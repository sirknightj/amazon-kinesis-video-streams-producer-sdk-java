package com.amazonaws.kinesisvideo.demoapp;

//import com.amazonaws.kinesisvideo.client.IPVersionFilter;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.demoapp.auth.AuthHelper;
import com.amazonaws.kinesisvideo.demoapp.debug.STSAuthHelper;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceState;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSourceConfiguration;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSourceSink;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceState;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.StreamCallbacks;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.model.CreateStreamRequest;
import com.amazonaws.services.kinesisvideo.model.DescribeStreamRequest;
import com.amazonaws.services.kinesisvideo.model.ResourceNotFoundException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.amazonaws.kinesisvideo.producer.TrackInfo;
import com.amazonaws.kinesisvideo.producer.*;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_AN_HOUR;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_SECOND;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.ABSOLUTE_TIMECODES;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_BITRATE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_REPLAY_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_STALENESS_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_TIMESCALE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.MAX_LATENCY;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NOT_ADAPTIVE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NO_KMS_KEY_ID;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECALCULATE_METRICS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECOVER_ON_FAILURE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.REQUEST_FRAGMENT_ACKS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.USE_FRAME_TIMECODES;


/**
 * KVS Streaming Benchmark
 *
 * This class demonstrates a simplified version of the LilyKvsStreamingProducer
 * focused on load and processing time for sending data to Kinesis Video Streams.
 *
 * It simulates a realistic scenario where:
 * 1. Two KVS clients are created at startup (one for internal streaming, one for external)
 * 2. Sessions come in dynamically and create media sources
 * 3. Each session has two tracks pumping at 50fps each (20ms chunks)
 * 4. We can test up to 500 concurrent streaming sessions
 * 5. Frames are created and processed in dedicated threads
 * 6. Each client has configurable number of streams
 */
public final class DemoAppActual {
    private static final Logger log = LogManager.getLogger(DemoAppActual.class);

    public static final int MAX_CONCURRENT_LIMIT = 400;
    public static final int MAX_STREAM_NAME = 800;
    public static final int MAX_INTERNAL_LIMIT = 0;
    public static final int MAX_EXTERNAL_LIMIT = 400;
    public static int INTERNAL_STREAM_COUNT = 1;
    public static int EXTERNAL_STREAM_COUNT = 1;

    // Configuration parameters
    private static final String STREAM_NAME_PREFIX = Optional.ofNullable(System.getProperty("kvs-stream-prefix")).orElse("benchmark-stream-");
    private static final int MAX_CONCURRENT_SESSIONS = Integer.parseInt(System.getProperty("max-concurrent-sessions", String.valueOf(MAX_CONCURRENT_LIMIT)));
    private static final int FRAME_SIZE_BYTES = Integer.parseInt(System.getProperty("frame-size-bytes", "5120"));
    private static final int FRAMES_PER_SECOND = Integer.parseInt(System.getProperty("frames-per-second", "10")); // 20ms chunks = 50fps
    private static final int BENCHMARK_DURATION_SECONDS = Integer.parseInt(System.getProperty("duration-seconds", "36000"));
    private static final int SESSION_DURATION_SECONDS = Integer.parseInt(System.getProperty("session-duration-seconds", "600"));
    private static final int NEW_SESSIONS_PER_SECOND = Integer.parseInt(System.getProperty("new-sessions-per-second", "20"));
    private static final int TRACK_COUNT = 2; // Two tracks as specified
    private static final int QUEUE_CAPACITY = Integer.parseInt(System.getProperty("queue-capacity", "200"));

    // Client-specific configuration
    private static final int INTERNAL_CLIENT_STREAMS = Integer.parseInt(System.getProperty("internal-client-streams", String.valueOf(MAX_INTERNAL_LIMIT)));
    private static final int EXTERNAL_CLIENT_STREAMS = Integer.parseInt(System.getProperty("external-client-streams", String.valueOf(MAX_EXTERNAL_LIMIT)));
    private static final String INTERNAL_STREAM_PREFIX = "internal-";
    private static final String EXTERNAL_STREAM_PREFIX = "";

    // Session tracking
    private static final AtomicInteger activeSessionCount = new AtomicInteger(0);
    private static final AtomicInteger totalSessionsCreated = new AtomicInteger(0);
    private static final AtomicInteger totalSessionsCompleted = new AtomicInteger(0);

    // Client-specific session tracking
    private static final AtomicInteger internalActiveSessionCount = new AtomicInteger(0);
    private static final AtomicInteger externalActiveSessionCount = new AtomicInteger(0);
    private static final AtomicInteger internalTotalSessionsCreated = new AtomicInteger(0);
    private static final AtomicInteger externalTotalSessionsCreated = new AtomicInteger(0);

    private static KinesisVideoClient internalKinesisVideoClient;
    private static KinesisVideoClient externalKinesisVideoClient;
    // Session management
    private static final ConcurrentHashMap<String, BenchmarkMediaSource> activeSessions = new ConcurrentHashMap<>();

    // Client type enum
    private enum ClientType {
        INTERNAL,
        EXTERNAL
    }

    private DemoAppActual() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void main(final String[] args) {
        try {
            log.info("Starting KVS Streaming Benchmark");
            log.info("Configuration: maxConcurrentSessions={}, frameSize={} bytes, fps={}, tracks={}, duration={} seconds, sessionDuration={} seconds, newSessionsPerSecond={}",
                    MAX_CONCURRENT_SESSIONS, FRAME_SIZE_BYTES, FRAMES_PER_SECOND, TRACK_COUNT, BENCHMARK_DURATION_SECONDS,
                    SESSION_DURATION_SECONDS, NEW_SESSIONS_PER_SECOND);
            log.info("Client configuration: internalClientStreams={}, externalClientStreams={}",
                    INTERNAL_CLIENT_STREAMS, EXTERNAL_CLIENT_STREAMS);

            // Create two Kinesis Video high level clients - one for internal streaming, one for external
            internalKinesisVideoClient = KinesisVideoJavaClientFactory
                    .createKinesisVideoClient(
                            Regions.US_WEST_2,
//                            AuthHelper.getSystemPropertiesCredentialsProvider());
//                            DefaultAWSCredentialsProviderChain.getInstance());
                            new STSAuthHelper());

            externalKinesisVideoClient = KinesisVideoJavaClientFactory
                    .createKinesisVideoClient(
                            Regions.US_WEST_2,
//                            AuthHelper.getSystemPropertiesCredentialsProvider());
//                            DefaultAWSCredentialsProviderChain.getInstance());
                            new STSAuthHelper());

            // Schedule metrics reporting
//            final ScheduledExecutorService metricsExecutor = Executors.newSingleThreadScheduledExecutor();
//            metricsExecutor.scheduleAtFixedRate(
//                    KvsStreamingBenchmarkMultiClient::reportMetrics,
//                    5, 5, TimeUnit.SECONDS);

            // Schedule session creation for internal client
            final ScheduledExecutorService internalSessionCreationExecutor = Executors.newSingleThreadScheduledExecutor();
            final long internalSessionCreationIntervalMs = 1000 / NEW_SESSIONS_PER_SECOND;

//            internalSessionCreationExecutor.scheduleAtFixedRate(
//                    () -> createNewSession(internalKinesisVideoClient, ClientType.INTERNAL),
//                    0, internalSessionCreationIntervalMs, TimeUnit.MILLISECONDS);

            // Schedule session creation for external client
            final ScheduledExecutorService externalSessionCreationExecutor = Executors.newSingleThreadScheduledExecutor();
            final long externalSessionCreationIntervalMs = 1000 / NEW_SESSIONS_PER_SECOND;

            externalSessionCreationExecutor.scheduleAtFixedRate(
                    () -> createNewSession(externalKinesisVideoClient, ClientType.EXTERNAL),
                    0, externalSessionCreationIntervalMs, TimeUnit.MILLISECONDS);

            // Run benchmark for specified duration
            log.info("Benchmark running for {} seconds...", BENCHMARK_DURATION_SECONDS);
            Thread.sleep(TimeUnit.SECONDS.toMillis(BENCHMARK_DURATION_SECONDS));

            // Stop creating new sessions
            log.info("Stopping session creation");
            internalSessionCreationExecutor.shutdown();
            externalSessionCreationExecutor.shutdown();

            // Wait for active sessions to complete
            log.info("Waiting for active sessions to complete...");
            int remainingActiveSessions = activeSessionCount.get();
            while (remainingActiveSessions > 0) {
                log.info("{} active sessions remaining (internal: {}, external: {})",
                        remainingActiveSessions,
                        internalActiveSessionCount.get(),
                        externalActiveSessionCount.get());
                Thread.sleep(1000);
                remainingActiveSessions = activeSessionCount.get();
            }

            // Final metrics report
            reportMetrics();

            // Shutdown executors
//            metricsExecutor.shutdown();

            log.info("Freeing clients");
            internalKinesisVideoClient.free();
            externalKinesisVideoClient.free();
            log.info("Done freeing clients");

        } catch (final KinesisVideoException | InterruptedException e) {
            log.error("Benchmark failed with exception", e);
            throw new RuntimeException(e);
        }

        System.exit(0);
    }

    /**
     * Creates a new streaming session if we're under the maximum concurrent sessions limit
     */
    private static void createNewSession(KinesisVideoClient kvsClient, ClientType clientType) {
        // Check if we're at the maximum concurrent sessions limit
        if (activeSessionCount.get() >= MAX_CONCURRENT_SESSIONS) {
            log.info("ignoring createNewSession, at max concurrent sessions");
            return;
        }

        // Check client-specific stream limits
        if (clientType == ClientType.INTERNAL &&
                internalActiveSessionCount.get() >= INTERNAL_CLIENT_STREAMS) {
            return;
        }

        if (clientType == ClientType.EXTERNAL &&
                externalActiveSessionCount.get() >= EXTERNAL_CLIENT_STREAMS) {
            return;
        }

        // Create a new session with a unique ID
        final String sessionId = (clientType == ClientType.INTERNAL) ?
                String.valueOf(INTERNAL_STREAM_COUNT) : String.valueOf(EXTERNAL_STREAM_COUNT);

        if (clientType == ClientType.INTERNAL) {
            INTERNAL_STREAM_COUNT++;
        }
        else {
            EXTERNAL_STREAM_COUNT++;
        }

        if (INTERNAL_STREAM_COUNT > MAX_STREAM_NAME) {
            INTERNAL_STREAM_COUNT = 1;
        }

        if (EXTERNAL_STREAM_COUNT > MAX_STREAM_NAME) {
            EXTERNAL_STREAM_COUNT = 1;
        }

        final String streamPrefix = (clientType == ClientType.INTERNAL) ?
                INTERNAL_STREAM_PREFIX : EXTERNAL_STREAM_PREFIX;
        final String streamName = STREAM_NAME_PREFIX + streamPrefix + sessionId;

        final AmazonKinesisVideo kvs = AmazonKinesisVideoClientBuilder.defaultClient();
        try {
            kvs.describeStream(new DescribeStreamRequest().withStreamName(streamName));
        } catch (ResourceNotFoundException ex) {
            kvs.createStream(new CreateStreamRequest().withStreamName(streamName).withDataRetentionInHours(0));
        } finally {
            kvs.shutdown();
        }

        try {
            // Create a new media source
            final BenchmarkMediaSource mediaSource = new BenchmarkMediaSource(
                    streamName,
                    sessionId,
                    FRAME_SIZE_BYTES,
                    FRAMES_PER_SECOND,
                    TRACK_COUNT,
                    QUEUE_CAPACITY);

            // Configure the media source
            final BenchmarkMediaSourceConfiguration configuration = new BenchmarkMediaSourceConfiguration(
                    FRAME_SIZE_BYTES,
                    FRAMES_PER_SECOND,
                    TRACK_COUNT,
                    QUEUE_CAPACITY);
            mediaSource.configure(configuration);

            // Register with KVS client
            kvsClient.registerMediaSource(mediaSource);

            // Start the media source
            mediaSource.start();

            // Add to active sessions
            activeSessions.put(streamName, mediaSource);
            activeSessionCount.incrementAndGet();
            totalSessionsCreated.incrementAndGet();

            // Update client-specific counters
            if (clientType == ClientType.INTERNAL) {
                internalActiveSessionCount.incrementAndGet();
                internalTotalSessionsCreated.incrementAndGet();
            } else {
                externalActiveSessionCount.incrementAndGet();
                externalTotalSessionsCreated.incrementAndGet();
            }

            // Schedule session completion
            scheduleSessionCompletion(streamName, sessionId, SESSION_DURATION_SECONDS, kvsClient, clientType);

            if (totalSessionsCreated.get() % 10 == 0) {
                log.info("Created {} session {}, active sessions: {} (internal: {}, external: {})",
                        clientType.toString().toLowerCase(),
                        sessionId,
                        activeSessionCount.get(),
                        internalActiveSessionCount.get(),
                        externalActiveSessionCount.get());
            }
        } catch (Exception e) {
            log.error("Failed to create {} session {}: {}",
                    clientType.toString().toLowerCase(),
                    sessionId,
                    e.getMessage(),
                    e);
        }
    }

    private static void stopStream(String streamName) {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            try {
                final BenchmarkMediaSource mediaSource = activeSessions.remove(streamName);
                if (mediaSource != null) {
                    mediaSource.stop();
                    activeSessionCount.decrementAndGet();
                    totalSessionsCompleted.incrementAndGet();
                    log.info("Stopping {}", streamName);

                    // Update client-specific counters based on stream name
                    if (streamName.contains(INTERNAL_STREAM_PREFIX)) {
                        internalActiveSessionCount.decrementAndGet();
                        internalKinesisVideoClient.unregisterMediaSource(mediaSource);

                    } else {
                        externalActiveSessionCount.decrementAndGet();
                        externalKinesisVideoClient.unregisterMediaSource(mediaSource);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to stop stream {}.",
                        streamName,
                        e.getMessage(),
                        e);
            }
        }, 10 ,TimeUnit.SECONDS);
    }

    /**
     * Schedules a session to complete after the specified duration
     */
    private static void scheduleSessionCompletion(String streamName, String sessionId, int durationSeconds, KinesisVideoClient kvsClient, ClientType clientType) {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            try {
                final BenchmarkMediaSource mediaSource = activeSessions.remove(streamName);
                if (mediaSource != null) {
                    mediaSource.stop();
                    kvsClient.unregisterMediaSource(mediaSource);
                    activeSessionCount.decrementAndGet();
                    totalSessionsCompleted.incrementAndGet();

                    // Update client-specific counters
                    if (clientType == ClientType.INTERNAL) {
                        internalActiveSessionCount.decrementAndGet();
                    } else {
                        externalActiveSessionCount.decrementAndGet();
                    }

                    if (totalSessionsCompleted.get() % 10 == 0) {
                        log.info("Completed {} session {}, active sessions: {} (internal: {}, external: {})",
                                clientType.toString().toLowerCase(),
                                sessionId,
                                activeSessionCount.get(),
                                internalActiveSessionCount.get(),
                                externalActiveSessionCount.get());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to complete {} session {}: {}",
                        clientType.toString().toLowerCase(),
                        sessionId,
                        e.getMessage(),
                        e);
            } finally {
                executor.shutdown();
            }
        }, durationSeconds, TimeUnit.SECONDS);
    }

    /**
     * Reports current benchmark metrics
     */
    private static void reportMetrics() {
        final int active = activeSessionCount.get();
        final int created = totalSessionsCreated.get();
        final int completed = totalSessionsCompleted.get();

        final int internalActive = internalActiveSessionCount.get();
        final int externalActive = externalActiveSessionCount.get();
        final int internalCreated = internalTotalSessionsCreated.get();
        final int externalCreated = externalTotalSessionsCreated.get();

        log.info("Benchmark status: activeSessions={} (internal={}, external={}), totalCreated={} (internal={}, external={}), totalCompleted={}",
                active, internalActive, externalActive, created, internalCreated, externalCreated, completed);
    }

    /**
     * Benchmark Media Source implementation
     */
    private static class BenchmarkMediaSource implements MediaSource {
        private final Logger log = LogManager.getLogger(BenchmarkMediaSource.class);
        private final String streamName;
        private final String sessionId;
        private final AtomicBoolean isRunning = new AtomicBoolean(false);
        private final Random random = new Random();
        private final ExecutorService executorService;
        private final BenchmarkStreamPublisher streamPublisher;
        private final BenchmarkFrameFactory frameFactory;

        private BenchmarkMediaSourceConfiguration configuration;
        private MediaSourceSink mediaSourceSink;
        private MediaSourceState mediaSourceState = MediaSourceState.READY;
        private long startTimeMs;

        /**
         * Creates a new BenchmarkMediaSource
         *
         * @param streamName The name of the stream
         * @param sessionId The session ID
         * @param frameSize The size of each frame in bytes
         * @param framesPerSecond The number of frames per second
         * @param trackCount The number of tracks
         * @param queueCapacity The capacity of the frame queue
         */
        public BenchmarkMediaSource(
                String streamName,
                String sessionId,
                int frameSize,
                int framesPerSecond,
                int trackCount,
                int queueCapacity) {
            this.streamName = streamName;
            this.sessionId = sessionId;
            this.executorService = Executors.newSingleThreadExecutor();
            this.frameFactory = new BenchmarkFrameFactory(frameSize);
            this.streamPublisher = new BenchmarkStreamPublisher(queueCapacity, frameFactory, streamName);
        }

        @Override
        public MediaSourceState getMediaSourceState() {
            return mediaSourceState;
        }

        @Override
        public MediaSourceConfiguration getConfiguration() {
            return configuration;
        }

        @Override
        public StreamInfo getStreamInfo() {
            TrackInfo[] tracks = new TrackInfo[2];

            for(int i =0; i < 2; i++){
                tracks[i] = new TrackInfo(i+1, "PCM_LT", "AudioTrack", UUID.randomUUID().toString().getBytes(), MkvTrackInfoType.AUDIO);
            }
            return new StreamInfo(0,
                    streamName,
                    StreamInfo.StreamingType.STREAMING_TYPE_REALTIME, //Streaming Type
                    "audio/L16", //This is not a valid type. Seenca should use track info to know the exact mime type
                    NO_KMS_KEY_ID, //KMS Key for encryption is set to null. KMS key of the KVS stream will be used.
                    0,
                    NOT_ADAPTIVE, //Adaptive flag
                    MAX_LATENCY, //maxLatency. 120 seconds by default.
                    1000 * HUNDREDS_OF_NANOS_IN_A_MILLISECOND, //Fragment duration
                    false, //keyFrameFragmentation, for PCM every frame is key frame so we dont fragment on keyframe
                    USE_FRAME_TIMECODES, //frameTimecodes, we generate the frame timecodes so this will be true
                    ABSOLUTE_TIMECODES, //absolutementTimes, use absolute fragment times
                    REQUEST_FRAGMENT_ACKS, //fragmentAcks, we need fragment acks
                    RECOVER_ON_FAILURE,//recoverOnError, As per KVS team recommendation this should be true
                    DEFAULT_BITRATE, //BitRate
                    10, //FrameRate
                    20 * HUNDREDS_OF_NANOS_IN_A_SECOND, //Set to 20 seconds
                    DEFAULT_REPLAY_DURATION, //Set to 20 seconds
                    DEFAULT_STALENESS_DURATION, //Set to 20 seconds
                    DEFAULT_TIMESCALE, //TimeScale
                    RECALCULATE_METRICS, //Recalculate Metrics
                    new Tag[0], //Tags to be put on the stream. We are not using these tags in our feature so set to empty
                    StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE,
                    UUID.randomUUID(),
                    tracks, false);
        }

        @Override
        public void initialize(@Nonnull MediaSourceSink mediaSourceSink) throws KinesisVideoException {
            this.mediaSourceSink = mediaSourceSink;
            this.streamPublisher.setMediaSourceSink(mediaSourceSink);
            this.mediaSourceState = MediaSourceState.INITIALIZED;
        }

        @Override
        public void configure(MediaSourceConfiguration mediaSourceConfiguration) {
            if (!(mediaSourceConfiguration instanceof BenchmarkMediaSourceConfiguration)) {
                throw new IllegalArgumentException("Configuration must be a BenchmarkMediaSourceConfiguration");
            }

            this.configuration = (BenchmarkMediaSourceConfiguration) mediaSourceConfiguration;
            this.mediaSourceState = MediaSourceState.READY;
        }

        @Override
        public void start() {
            if (isRunning.compareAndSet(false, true)) {
                this.mediaSourceState = MediaSourceState.RUNNING;
                this.startTimeMs = System.currentTimeMillis();

                // Start the publisher
                this.streamPublisher.start();

                // Start frame generation
                executorService.execute(this::generateFrames);
            }
        }

        @Override
        public void stop() {
            if (isRunning.compareAndSet(true, false)) {
                this.mediaSourceState = MediaSourceState.STOPPED;

                // Stop the publisher
                this.streamPublisher.stop();

                // Shutdown executor and wait for termination
                executorService.shutdown();
            }
        }

        @Override
        public boolean isStopped() {
            return !isRunning.get();
        }

        @Override
        public void free() throws KinesisVideoException {
            stop();
        }

        @Override
        public MediaSourceSink getMediaSourceSink() {
            return mediaSourceSink;
        }

        @Nullable
        @Override
        public StreamCallbacks getStreamCallbacks() {
            return new StreamCallbacks() {
                public void streamConnectionStale(long callbackHandle) {
                    log.warn("Stream connection stale");
                }

                public void streamErrorReport(long callbackHandle, long streamHandle, long statusCode) {
                    log.error("Stream error: status={}", statusCode);
                }

                public void streamLatencyPressure(long callbackHandle) {
//                    log.warn("Stream latency pressure");
                }

                public void streamDataAvailable(long callbackHandle, long streamHandle, long availableSize) {
                    // Removed spammy logging
                }

                public void fragmentAckReceived(long uploadHandle, KinesisVideoFragmentAck fragmentAck) {
                    int resultCode = fragmentAck.getResult();
//                    emitCallbackMetric(fragmentAck, resultCode);

                    String logMsg = String.format("FragmentAckReceived UploadHandle %s, FragmentAck [AckType %s, Sequence %s, Timestamp %s, Result %s]",
                            uploadHandle,
                            fragmentAck.getAckType().getIntType(),
                            fragmentAck.getSequenceNumber(),
                            fragmentAck.getTimestamp(),
                            fragmentAck.getResult());
                    if (fragmentAck.getAckType().getIntType() == FragmentAckType.FRAGMENT_ACK_TYPE_UNDEFINED
                            || fragmentAck.getAckType().getIntType() == FragmentAckType.FRAGMENT_ACK_TYPE_ERROR) {
                        log.error(logMsg);
                    } else {
                        log.debug(logMsg);
                    }

//                    if (!fragmentNumberFuture.isDone()) {
//                        FragmentInfo fragmentInfo = new FragmentInfo(Instant.ofEpochMilli(fragmentAck.getTimestamp()), fragmentAck.getSequenceNumber());
//                        fragmentNumberFuture.complete(fragmentInfo);
//                        log.info("received Fragment Number {}", internalKvsLoggingConfigData, fragmentAck.getSequenceNumber());
//                    }

//                    this.currentFragmentNumber = fragmentAck.getSequenceNumber();
                    stopStreamingIfError(fragmentAck, resultCode);
                }


                private void stopStreamingIfError(KinesisVideoFragmentAck fragmentAck, int resultCode) {
                    if (fragmentAck.getAckType().getIntType() == FragmentAckType.FRAGMENT_ACK_TYPE_ERROR
                            && (resultCode >= 4000 && resultCode <= 4999)) {
//                        if (!kvsFragmentErrorMonitorFuture.isDone()) {
//                            kvsFragmentErrorMonitorFuture.completeExceptionally(new StreamingException(fragmentAck.getSequenceNumber(), StreamingFailure.KMS_KEY_ISSUE));
                        log.error("[{}]: received KVS error code {} on Fragment {}, Stopping Stream.", streamName, fragmentAck.getResult(), fragmentAck.getSequenceNumber());
                        stop();
                        stopStream(streamName);
//                        }
                    }
                }

                public void bufferDurationOverflowPressure(long callbackHandle) {
//                    log.warn("Buffer duration overflow pressure");
                }

                public void streamClosed(long callbackHandle) {
                    log.info("[{}] Stream closed", streamName);
                }

                public void streamReady() {
                    log.info("[{}] Stream ready ", streamName);
                }

                public void droppedFragmentReport(long callbackHandle) {
//                    log.warn("Dropped fragment report");
                }

                public void droppedFrameReport(long callbackHandle) {
//                    log.warn("Dropped frame report");
                }

                public void streamUnderflowReport() {
//                    log.warn("Stream underflow report");
                }
            };
        }

        /**
         * Generates frames for all tracks at the specified frame rate
         */
        private void generateFrames() {
            final long frameIntervalMs = 1000 / configuration.getFramesPerSecond();
            long nextFrameTimeMs = System.currentTimeMillis();

            try {
                while (isRunning.get()) {
                    final long now = System.currentTimeMillis();

                    if (now >= nextFrameTimeMs) {
                        // Generate frames for each track
                        for (int trackId = 0; trackId < configuration.getTrackCount(); trackId++) {
                            // Create streaming chunk
                            final BenchmarkStreamingChunk chunk = createStreamingChunk(trackId+1);

                            // Process chunk
                            streamPublisher.processChunk(chunk);
                        }

                        // Schedule next frame batch
                        nextFrameTimeMs = now + frameIntervalMs;
                    }

                    // Minimal sleep to avoid busy waiting
                    if (now < nextFrameTimeMs - 1) {
                        Thread.sleep(0, 100000); // 0.1ms sleep
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error generating frames: {}", e.getMessage(), e);
            }
        }

        /**
         * Creates a streaming chunk for the specified track
         *
         * @param trackId The track ID
         * @return A new streaming chunk
         */
        private BenchmarkStreamingChunk createStreamingChunk(int trackId) {
            final long timestamp = System.currentTimeMillis();
            final byte[] data = new byte[configuration.getFrameSize()];
            random.nextBytes(data);

            return new BenchmarkStreamingChunk(
                    timestamp,
                    data,
                    trackId,
                    100 // 20ms duration
            );
        }
    }

    /**
     * Benchmark Stream Publisher implementation
     */
    private static class BenchmarkStreamPublisher {
        private final Logger log = LogManager.getLogger(BenchmarkStreamPublisher.class);
        private final BlockingQueue<BenchmarkStreamingChunk> chunkQueue;
        private final BenchmarkFrameFactory frameFactory;
        private final AtomicBoolean isRunning = new AtomicBoolean(false);
        private final ExecutorService executorService = Executors.newSingleThreadExecutor();

        private MediaSourceSink mediaSourceSink;
        private final String streamName;

        /**
         * Creates a new BenchmarkStreamPublisher
         *
         * @param queueCapacity The capacity of the chunk queue
         * @param frameFactory The frame factory
         */
        public BenchmarkStreamPublisher(int queueCapacity, BenchmarkFrameFactory frameFactory, String streamName) {
            this.chunkQueue = new ArrayBlockingQueue<>(queueCapacity);
            this.frameFactory = frameFactory;
            this.streamName = streamName;
        }

        /**
         * Sets the media source sink
         *
         * @param mediaSourceSink The media source sink
         */
        public void setMediaSourceSink(MediaSourceSink mediaSourceSink) {
            this.mediaSourceSink = mediaSourceSink;
        }

        /**
         * Starts the publisher
         */
        public void start() {
            if (isRunning.compareAndSet(false, true)) {
                executorService.execute(this::processChunks);
            }
        }

        /**
         * Stops the publisher
         */
        public void stop() {
            if (isRunning.compareAndSet(true, false)) {
                executorService.shutdown();
            }
        }

        /**
         * Processes a streaming chunk
         *
         * @param chunk The chunk to process
         */
        public void processChunk(BenchmarkStreamingChunk chunk) {
            if (!isRunning.get()) {
                return;
            }

            boolean added = chunkQueue.offer(chunk);
            if (!added) {
                log.warn("[{}]: Chunk queue full, dropping chunk", streamName);
            }
        }

        /**
         * Processes chunks from the queue
         */
        private void processChunks() {
            try {
                while (isRunning.get()) {
                    BenchmarkStreamingChunk chunk = chunkQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        processChunkInternal(chunk);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[{}]: Error processing chunks: {}", streamName, e.getMessage(), e);
            }
        }

        /**
         * Processes a chunk internally
         *
         * @param chunk The chunk to process
         */
        private void processChunkInternal(BenchmarkStreamingChunk chunk) {
            try {
                final long startProcessingMs = System.currentTimeMillis();

                // Create frame
                KinesisVideoFrame frame = frameFactory.createFrame(chunk);

                // Send frame
                mediaSourceSink.onFrame(frame);

                // Frame sent successfully
            } catch (Exception e) {
                log.error("[{}]: Error processing chunk: {}", streamName, e.getMessage(), e);
            }
        }
    }

    /**
     * Benchmark Frame Factory implementation
     */
    private static class BenchmarkFrameFactory {
        private final Map<Long, Long> lastTimestampByTrack = new ConcurrentHashMap<>();
        private final AtomicLong frameIndex = new AtomicLong(0);
        private final int frameSize;

        /**
         * Creates a new BenchmarkFrameFactory
         *
         * @param frameSize The size of each frame in bytes
         */
        public BenchmarkFrameFactory(int frameSize) {
            this.frameSize = frameSize;
        }

        /**
         * Creates a frame from a streaming chunk
         *
         * @param chunk The streaming chunk
         * @return A new KinesisVideoFrame
         */
        public KinesisVideoFrame createFrame(BenchmarkStreamingChunk chunk) {
            final long index = frameIndex.getAndIncrement();
            final long trackId = chunk.getTrackId();

            // Get the original timestamp (in 100ns units)
            final long originalTimestamp = chunk.getTimestamp() * 10000L;

            // Ensure monotonically increasing timestamps per track
            long adjustedTimestamp = originalTimestamp;
            Long lastTimestamp = lastTimestampByTrack.get(trackId);

            if (lastTimestamp != null && adjustedTimestamp <= lastTimestamp) {
                // If this timestamp is not greater than the last one for this track,
                // we need to adjust it to ensure it's strictly greater
                adjustedTimestamp = lastTimestamp + 1;
            }

            // Store the adjusted timestamp for this track
            lastTimestampByTrack.put(trackId, adjustedTimestamp);

            // Create the frame
            return new KinesisVideoFrame(
                    (int) index,
                    1, // Key frame flag
                    adjustedTimestamp,
                    adjustedTimestamp,
                    chunk.getDurationMs() * 10000L, // Convert to 100ns units
                    ByteBuffer.wrap(chunk.getData()),
                    trackId
            );
        }
    }

    /**
     * Benchmark Streaming Chunk
     */
    private static class BenchmarkStreamingChunk {
        private final long timestamp;
        private final byte[] data;
        private final long trackId;
        private final long durationMs;

        /**
         * Creates a new BenchmarkStreamingChunk
         *
         * @param timestamp The timestamp
         * @param data The data
         * @param trackId The track ID
         * @param durationMs The duration in milliseconds
         */
        public BenchmarkStreamingChunk(long timestamp, byte[] data, long trackId, long durationMs) {
            this.timestamp = timestamp;
            this.data = data;
            this.trackId = trackId;
            this.durationMs = durationMs;
        }

        /**
         * Gets the timestamp
         *
         * @return The timestamp
         */
        public long getTimestamp() {
            return timestamp;
        }

        /**
         * Gets the data
         *
         * @return The data
         */
        public byte[] getData() {
            return data;
        }

        /**
         * Gets the track ID
         *
         * @return The track ID
         */
        public long getTrackId() {
            return trackId;
        }

        /**
         * Gets the duration in milliseconds
         *
         * @return The duration in milliseconds
         */
        public long getDurationMs() {
            return durationMs;
        }
    }

    /**
     * Benchmark Media Source Configuration
     */
    private static class BenchmarkMediaSourceConfiguration implements MediaSourceConfiguration {
        private final int frameSize;
        private final int framesPerSecond;
        private final int trackCount;
        private final int queueCapacity;

        /**
         * Creates a new BenchmarkMediaSourceConfiguration
         *
         * @param frameSize The size of each frame in bytes
         * @param framesPerSecond The number of frames per second
         * @param trackCount The number of tracks
         * @param queueCapacity The capacity of the frame queue
         */
        public BenchmarkMediaSourceConfiguration(int frameSize, int framesPerSecond, int trackCount, int queueCapacity) {
            this.frameSize = frameSize;
            this.framesPerSecond = framesPerSecond;
            this.trackCount = trackCount;
            this.queueCapacity = queueCapacity;
        }

        /**
         * Gets the frame size
         *
         * @return The frame size
         */
        public int getFrameSize() {
            return frameSize;
        }

        /**
         * Gets the frames per second
         *
         * @return The frames per second
         */
        public int getFramesPerSecond() {
            return framesPerSecond;
        }

        /**
         * Gets the track count
         *
         * @return The track count
         */
        public int getTrackCount() {
            return trackCount;
        }

        /**
         * Gets the queue capacity
         *
         * @return The queue capacity
         */
        public int getQueueCapacity() {
            return queueCapacity;
        }

        @Override
        public String getMediaSourceDescription() {
            return "BytesMediaSource";
        }

        @Override
        public String getMediaSourceType() {
            return "MEDIA_SOURCE_TYPE";
        }
    }
}