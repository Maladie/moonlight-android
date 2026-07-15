package com.limelight.console;

/** Replacement boundary between Console Home and the active stream runtime. */
interface ConsoleStreamRuntime {
    void launch(ConsoleLaunchContract.Request request);
    void returnToActiveStream();
}
