package com.limelight.console;

/** One in-flight system dictation request, bound to the DM that started it. */
final class CommunityDictationSession {
    private boolean active;
    private long recipientId;
    private long directMessageGeneration;

    boolean begin(long recipientId, long directMessageGeneration) {
        if (active || recipientId <= 0 || directMessageGeneration <= 0) return false;
        active = true;
        this.recipientId = recipientId;
        this.directMessageGeneration = directMessageGeneration;
        return true;
    }

    boolean isActive() { return active; }
    long recipientId() { return recipientId; }
    long directMessageGeneration() { return directMessageGeneration; }

    boolean matches(long recipientId, long directMessageGeneration) {
        return active && this.recipientId == recipientId
                && this.directMessageGeneration == directMessageGeneration;
    }

    void finish() {
        active = false;
        recipientId = 0;
        directMessageGeneration = 0;
    }
}
