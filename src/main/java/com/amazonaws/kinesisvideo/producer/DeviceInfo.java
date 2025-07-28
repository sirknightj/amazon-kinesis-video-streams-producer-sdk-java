package com.amazonaws.kinesisvideo.producer;

import com.amazonaws.kinesisvideo.internal.producer.jni.NativeKinesisVideoProducerJni;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.amazonaws.kinesisvideo.common.preconditions.Preconditions;

/**
 * Device information object.
 *
 * NOTE: This should follow the structure defined in /client/Include.h
 *
 * NOTE: Suppressing Findbug error as this code will be accessed from native codebase.
 */
@SuppressFBWarnings("EI_EXPOSE_REP")
public class DeviceInfo {
    /**
     * Current version for the structure as defined in the native code
     */
    public static final int DEVICE_INFO_CURRENT_VERSION = 1;

    private int mVersion;
    private String mName;
    private StorageInfo mStorageInfo;
    private int mStreamCount;
    private Tag[] mTags;
    private String mClientId;
    private ClientInfo mClientInfo;

    /**
     * Use {@link #createDeviceInfoV0} instead.
     */
    @Deprecated
    public DeviceInfo(int version, @Nullable final String name, @Nonnull final StorageInfo storageInfo,
            int streamCount, @Nullable final Tag[] tags) {
        this(version, name, storageInfo, streamCount, tags,
                "JNI " + NativeKinesisVideoProducerJni.EXPECTED_LIBRARY_VERSION, new ClientInfo());
    }

    /**
     * Use {@link #createDeviceInfoV1} instead.
     */
    @Deprecated
    public DeviceInfo(int version, @Nullable final String name, @Nonnull final StorageInfo storageInfo,
                      int streamCount, @Nullable final Tag[] tags, @Nonnull final String clientId,
                      @Nonnull final ClientInfo clientInfo) {
        mStorageInfo = Preconditions.checkNotNull(storageInfo);
        mName = name;
        mTags = tags;
        mVersion = version;
        mStreamCount = streamCount;
        mClientId = clientId;
        mClientInfo = clientInfo;
    }

    public static DeviceInfo createDeviceInfoV0(final String name, final StorageInfo storageInfo,
                                                final int streamCount, final Tag[] tags) {
        return new DeviceInfo(name, storageInfo, streamCount, tags);
    }

    // V0 constructor
    private DeviceInfo(final String name, final StorageInfo storageInfo,
                       final int streamCount, final Tag[] tags) {
        mVersion = 0;
        mStorageInfo = Preconditions.checkNotNull(storageInfo);
        mName = name;
        mTags = tags;
        mStreamCount = streamCount;
    }

    public static DeviceInfo createDeviceInfoV1(final String name, final StorageInfo storageInfo, final int streamCount, final Tag[] tags, final String clientId, final ClientInfo clientInfo) {
        return new DeviceInfo(name, storageInfo, streamCount, tags, clientId, clientInfo);
    }

    // V1 constructor
    private DeviceInfo(final String name, final StorageInfo storageInfo,
                       final int streamCount, final Tag[] tags,
                       final String clientId, final ClientInfo clientInfo) {
        this(name, storageInfo, streamCount, tags);

        mVersion = 1;
        mClientId = clientId;
        mClientInfo = clientInfo;
    }

    public int getVersion() {
        return mVersion;
    }

    public String getName() {
        return mName;
    }

    @Nonnull
    public StorageInfo getStorageInfo() {
        return mStorageInfo;
    }

    public int getStreamCount() {
        return mStreamCount;
    }

    public int getStorageInfoVersion() {
        return mStorageInfo.getVersion();
    }

    public int getDeviceStorageType() {
        return mStorageInfo.getDeviceStorageType();
    }

    public long getStorageSize() {
        return mStorageInfo.getStorageSize();
    }

    public int getSpillRatio() {
        return mStorageInfo.getSpillRatio();
    }

    @Nullable
    public String getRootDirectory() {
        return mStorageInfo.getRootDirectory();
    }

    @Nullable
    public Tag[] getTags() {
        return mTags;
    }

    @Nonnull
    public String getClientId() {
        return mClientId;
    }

    @Nonnull
    public ClientInfo getClientInfo() {
        return mClientInfo;
    }
}
