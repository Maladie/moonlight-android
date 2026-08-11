package com.limelight.nvstream.av.video;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Keeps moonlight-common-c connected while the Android video decoder is temporarily absent.
 * Calls from the native decode thread are serialized against renderer replacement.
 */
public final class SwitchableVideoDecoderRenderer extends VideoDecoderRenderer {
    private static final VideoDecoderRenderer DISCARD = new VideoDecoderRenderer() {
        @Override public int setup(int format, int width, int height, int redrawRate) { return 0; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public int submitDecodeUnit(byte[] data, int length, int type, int frame,
                                               int frameType, char hostLatency, long receiveUs,
                                               long enqueueUs, long presentationUs) {
            return 0;
        }
        @Override public void cleanup() { }
        @Override public int getCapabilities() { return 0; }
        @Override public void setHdrMode(boolean enabled, byte[] metadata) { }
    };

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int capabilities;
    private VideoDecoderRenderer delegate;
    private boolean configured;
    private boolean started;
    private int format;
    private int width;
    private int height;
    private int redrawRate;
    private boolean hdrEnabled;
    private byte[] hdrMetadata;

    public SwitchableVideoDecoderRenderer(VideoDecoderRenderer initial) {
        delegate = initial;
        capabilities = initial.getCapabilities();
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        lock.writeLock().lock();
        try {
            this.format = format;
            this.width = width;
            this.height = height;
            this.redrawRate = redrawRate;
            int result = delegate.setup(format, width, height, redrawRate);
            configured = result == 0;
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void start() {
        lock.writeLock().lock();
        try {
            delegate.start();
            started = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Attaches a fresh decoder and initializes it to the negotiated stream format. */
    public boolean attach(VideoDecoderRenderer replacement) {
        VideoDecoderRenderer previous;
        boolean disposeStarted;
        boolean disposeConfigured;
        lock.writeLock().lock();
        try {
            if (configured && replacement.setup(format, width, height, redrawRate) != 0) {
                replacement.cleanup();
                return false;
            }
            replacement.setHdrMode(hdrEnabled, hdrMetadata == null ? null : hdrMetadata.clone());
            if (started) replacement.start();
            previous = delegate;
            disposeStarted = started;
            disposeConfigured = configured;
            delegate = replacement;
        } finally {
            lock.writeLock().unlock();
        }
        dispose(previous, disposeStarted, disposeConfigured);
        return true;
    }

    /** Switches immediately to a decoder-less sink and releases the hardware codec. */
    public void detach() {
        VideoDecoderRenderer previous;
        boolean disposeStarted;
        boolean disposeConfigured;
        lock.writeLock().lock();
        try {
            previous = delegate;
            disposeStarted = started;
            disposeConfigured = configured;
            delegate = DISCARD;
        } finally {
            lock.writeLock().unlock();
        }
        dispose(previous, disposeStarted, disposeConfigured);
    }

    private void dispose(VideoDecoderRenderer renderer, boolean shouldStop,
                         boolean shouldCleanup) {
        if (renderer == null || renderer == DISCARD) return;
        if (shouldStop) renderer.stop();
        if (shouldCleanup) renderer.cleanup();
    }

    @Override
    public void stop() {
        lock.writeLock().lock();
        try {
            if (started) delegate.stop();
            started = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int submitDecodeUnit(byte[] data, int length, int type, int frame, int frameType,
                                char hostLatency, long receiveUs, long enqueueUs,
                                long presentationUs) {
        lock.readLock().lock();
        try {
            return delegate.submitDecodeUnit(data, length, type, frame, frameType, hostLatency,
                    receiveUs, enqueueUs, presentationUs);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void cleanup() {
        lock.writeLock().lock();
        try {
            if (configured) delegate.cleanup();
            configured = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override public int getCapabilities() { return capabilities; }

    @Override
    public void setHdrMode(boolean enabled, byte[] metadata) {
        lock.writeLock().lock();
        try {
            hdrEnabled = enabled;
            hdrMetadata = metadata == null ? null : metadata.clone();
            delegate.setHdrMode(enabled, metadata);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isDetached() {
        lock.readLock().lock();
        try {
            return delegate == DISCARD;
        } finally {
            lock.readLock().unlock();
        }
    }
}
