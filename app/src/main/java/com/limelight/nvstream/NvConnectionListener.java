package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    void stageFailed(String stage, int portFlags, int errorCode);
    /**
     * The Gateway refused the control operation (acquire, or HTTP 409 on reconcile or
     * complete); reason is its stable code or empty. {@code nothingStarted}: the refusal
     * answered the start's own acquire, so this connection sent no effect to the host and
     * holds no permit: there is nothing of it to clean up or close.
     */
    void controlRejected(String stage, String reason, boolean nothingStarted);
    /**
     * A start this connection gave up without reading its permit was found started on the
     * host under that generation; the stage still fails, and the stream's owner closes it.
     */
    default void startLeftOnHost(long controlGeneration) { }
    
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    
    void displayMessage(String message);
    void displayTransientMessage(String message);

    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    void setControllerLED(short controllerNumber, byte r, byte g, byte b);
}
