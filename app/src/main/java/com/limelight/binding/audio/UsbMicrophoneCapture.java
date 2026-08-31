package com.limelight.binding.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.GatewayTransport;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UsbMicrophoneCapture {
    public static final int FRAME_BYTES = 1920;
    private static final int MAX_QUEUED_FRAMES = 6;

    public interface Listener { void onMicrophoneState(UsbMicrophoneCapture source, String state); }

    private final Context context;
    private final GatewayConnection connection;
    private final Listener listener;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final AudioManager audioManager;
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile boolean running;
    private AudioRecord record;
    private GatewayTransport.MicrophoneStream stream;
    private AudioDeviceCallback deviceCallback;

    public UsbMicrophoneCapture(Context context, GatewayConnection connection, Listener listener) {
        this.context = context.getApplicationContext();
        this.connection = connection;
        this.listener = listener;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void start() {
        synchronized (this) {
            if (running || connection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            failureReported.set(false);
            running = true;
        }
        new Thread(this::runCapture, "MoonWaker-USB-Microphone").start();
    }

    public void stop() {
        AudioRecord closingRecord;
        GatewayTransport.MicrophoneStream closingStream;
        AudioDeviceCallback closingCallback;
        synchronized (this) {
            running = false;
            closingRecord = record;
            record = null;
            closingStream = stream;
            stream = null;
            closingCallback = deviceCallback;
            deviceCallback = null;
        }
        if (closingRecord != null) {
            try { closingRecord.stop(); } catch (IllegalStateException ignored) { }
            closingRecord.release();
        }
        synchronized (frames) { frames.clear(); frames.notifyAll(); }
        closeStreamAsync(closingStream);
        if (closingCallback != null) audioManager.unregisterAudioDeviceCallback(closingCallback);
    }

    private void runCapture() {
        boolean recording = false;
        try {
            JSONObject capability = new GatewayTransport().getJson(
                    connection, "/api/v1/capabilities", 4_000)
                    .optJSONObject("capabilities");
            JSONObject microphone = capability == null ? null
                    : capability.optJSONObject("microphone");
            if (microphone == null || !microphone.optBoolean("available")) {
                String reason = microphone == null ? "unavailable"
                        : microphone.optString("reason", "unavailable");
                fail("worker_missing".equals(reason) ||
                        "steam_endpoint_missing_or_ambiguous_or_unsupported".equals(reason)
                        ? reason : "unavailable");
                return;
            }
            AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            AudioDeviceInfo device = soleUsbInput(inputs);
            if (device == null) {
                fail(usbInputCount(inputs) == 0 ? "usb_missing" : "usb_multiple");
                return;
            }
            int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord candidate = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, FRAME_BYTES * MAX_QUEUED_FRAMES)).build();
            if (candidate.getState() != AudioRecord.STATE_INITIALIZED || !candidate.setPreferredDevice(device)) {
                candidate.release(); fail("usb_unavailable"); return;
            }
            AudioDeviceCallback callback = createDeviceCallback();
            synchronized (this) {
                if (!running) { candidate.release(); return; }
                record = candidate;
                deviceCallback = callback;
                audioManager.registerAudioDeviceCallback(callback, null);
            }
            GatewayTransport.MicrophoneStream activeStream = new GatewayTransport()
                    .openMicrophoneStream(connection, UUID.randomUUID().toString());
            synchronized (this) {
                if (!running || record != candidate) {
                    closeStreamAsync(activeStream);
                    return;
                }
                stream = activeStream;
            }
            Thread writer = new Thread(() -> writeFrames(activeStream), "MoonWaker-Microphone-Upload");
            writer.start();
            candidate.startRecording();
            recording = true;
            synchronized (this) {
                if (!running || record != candidate || stream != activeStream) return;
                if (listener != null) listener.onMicrophoneState(this, "active");
            }
            while (running) {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED) {
                    fail("permission_revoked");
                    break;
                }
                byte[] frame = new byte[FRAME_BYTES];
                int offset = 0;
                while (running && offset < frame.length) {
                    int read = candidate.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                    if (read <= 0) throw new IllegalStateException("Audio capture stopped");
                    offset += read;
                }
                if (offset == frame.length) enqueue(frame);
            }
        } catch (Exception ignored) {
            fail(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED ? "permission_revoked"
                    : recording ? "usb_unavailable" : "error");
        }
        finally { stop(); }
    }

    private void writeFrames(GatewayTransport.MicrophoneStream activeStream) {
        try {
            while (running) {
                byte[] frame;
                synchronized (frames) {
                    while (running && frames.isEmpty()) frames.wait(100);
                    frame = frames.pollFirst();
                }
                if (frame != null) activeStream.writeFrame(frame);
            }
        } catch (Exception ignored) { fail("error"); stop(); }
    }

    private void enqueue(byte[] frame) {
        synchronized (frames) {
            enqueueBounded(frames, frame);
            frames.notifyAll();
        }
    }

    static void enqueueBounded(ArrayDeque<byte[]> queue, byte[] frame) {
        while (queue.size() >= MAX_QUEUED_FRAMES) queue.removeFirst();
        queue.addLast(frame);
    }

    private AudioDeviceCallback createDeviceCallback() {
        return new AudioDeviceCallback() {
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                for (AudioDeviceInfo device : removed) {
                    if (device.isSource() && (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET)) {
                        fail("usb_removed"); stop(); return;
                    }
                }
            }
        };
    }

    private void closeStreamAsync(GatewayTransport.MicrophoneStream closingStream) {
        if (closingStream == null) return;
        new Thread(() -> {
            try { closingStream.close(); } catch (Exception ignored) { }
        }, "MoonWaker-Microphone-Close").start();
    }

    private void fail(String state) {
        synchronized (this) {
            if (running && failureReported.compareAndSet(false, true) && listener != null) {
                listener.onMicrophoneState(this, state);
            }
        }
    }

    public static AudioDeviceInfo soleUsbInput(AudioDeviceInfo[] devices) {
        AudioDeviceInfo match = null;
        if (devices == null) return null;
        for (AudioDeviceInfo device : devices) {
            if (!device.isSource() || (device.getType() != AudioDeviceInfo.TYPE_USB_DEVICE &&
                    device.getType() != AudioDeviceInfo.TYPE_USB_HEADSET)) continue;
            if (match != null) return null;
            match = device;
        }
        return match;
    }

    public static int usbInputCount(AudioDeviceInfo[] devices) {
        int count = 0;
        if (devices == null) return 0;
        for (AudioDeviceInfo device : devices) {
            if (device.isSource() && (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                    || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET)) count++;
        }
        return count;
    }
}
