package com.limelight.binding.input;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ControllerHandlerInputSuppressionContractTest {
    @Test public void releaseAndSuppressNeutralizesEveryContextBeforeClosingGate()
            throws IOException {
        String source = source();
        String publicMethod = between(source,
                "public void releaseAllControllerInputsAndSuppress()",
                "public boolean isInputSuppressed()");
        String release = between(source, "private void releaseAllControllerInputs()",
                "private static void resetControllerInput(");

        assertTrue(publicMethod.indexOf("inputSuppressed = false")
                < publicMethod.indexOf("releaseAllControllerInputs()"));
        assertTrue(publicMethod.indexOf("releaseAllControllerInputs()")
                < publicMethod.lastIndexOf("inputSuppressed = true"));
        assertTrue(release.contains("resetControllerInput(inputDeviceContexts.valueAt(i))"));
        assertTrue(release.contains("resetControllerInput(usbDeviceContexts.valueAt(i))"));
        assertTrue(release.contains("resetControllerInput(defaultContext)"));
        assertTrue(release.contains("sendControllerInputPacket(inputDeviceContexts.valueAt(i))"));
        assertTrue(release.contains("sendControllerInputPacket(usbDeviceContexts.valueAt(i))"));
        assertTrue(release.contains("sendControllerInputPacket(defaultContext)"));
    }

    @Test public void holdOverlayReusesTheSameReleaseHelper() throws IOException {
        String source = source();
        String hold = between(source, "private void startOverlayMenuHoldDetection(",
                "private void cancelOverlayMenuHoldDetection()");

        assertTrue(hold.contains("releaseAllControllerInputs()"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        return source.substring(from, source.indexOf(end, from));
    }

    private static String source() throws IOException {
        Path path = Paths.get(
                "src/main/java/com/limelight/binding/input/ControllerHandler.java");
        if (!Files.exists(path)) path = Paths.get("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
