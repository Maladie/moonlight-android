package com.limelight;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** Reads this module's sources for the remaining structural tests. */
public final class SourceText {
    /** Gradle runs unit tests in the module directory; paths are relative to it. */
    public static final Path MODULE = Paths.get("").toAbsolutePath();
    public static final Path MAIN_JAVA = MODULE.resolve("src/main/java/com/limelight");

    private SourceText() { }

    /** A module-relative file, such as {@code src/main/res/values/strings.xml}. */
    public static String module(String relative) {
        return read(MODULE.resolve(relative));
    }

    /** A main source under {@code com/limelight}, such as {@code console/ConsoleActivity.java}. */
    public static String main(String relative) {
        return read(MAIN_JAVA.resolve(relative));
    }

    public static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(path.toString(), e);
        }
    }

    /** Each non-empty part is present, and starts after the previous one ends. */
    public static void assertOrdered(String source, String... parts) {
        int from = 0;
        for (String part : parts) {
            assertTrue("empty part", !part.isEmpty());
            int next = source.indexOf(part, from);
            assertTrue("missing or out of order: " + part, next >= 0);
            from = next + part.length();
        }
    }
}
