package com.limelight.nvstream;

public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    void stageFailed(String stage, int portFlags, int errorCode);
    /** The Gateway refused the control operation (HTTP 409 on acquire, reconcile or complete); reason is its stable code or empty. */
    void controlRejected(String stage, String reason);
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
