package com.limelight;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.limelight.SourceText.MAIN_JAVA;
import static com.limelight.SourceText.main;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** MW-05: one owner per session fact; the Activities and process statics keep no copy. */
public class SessionArchitectureGuardTest {
    /**
     * Host-reported running app/game reads left in ConsoleActivity, per method. Game reads none.
     * K5 owner decision 8: the four decision reads move to the Gateway's observation once the
     * Gateway reports the Sunshine app id (host 0.7.134); until then they stay. The rest only
     * feed the model or display.
     */
    private static final Map<String, Integer> ALLOWED_RUNNING_READS = new TreeMap<>();
    static {
        // Decision 8 (until host 0.7.134): close-session offer on the host tile.
        ALLOWED_RUNNING_READS.put("showHostSelectionOptions", 1);
        // Decision 8: close-session in quick launch.
        ALLOWED_RUNNING_READS.put("showQuickLaunchItemActions", 1);
        // Decision 8: saving the suspended session before host sleep.
        ALLOWED_RUNNING_READS.put("requestHostSleep", 1);
        // Decision 8: confirmTerminateSession guard.
        ALLOWED_RUNNING_READS.put("confirmTerminateSession", 1);
        // Display: pairing failure message names an in-game host.
        ALLOWED_RUNNING_READS.put("pairHostManually", 1);
        // Display: running badge on Sunshine app cards.
        ALLOWED_RUNNING_READS.put("renderApps", 1);
        // Display: which app actions an app card shows.
        ALLOWED_RUNNING_READS.put("showAppActions", 2);
        // Display: running game named in the host status (projection's display fact).
        ALLOWED_RUNNING_READS.put("hostStatus", 2);
        // Model input: the poll is reported to SessionModel (decision 3: 0 = transport lost).
        ALLOWED_RUNNING_READS.put("reportHostPoll", 1);
        // Model input: the poll's value handed to the session projection.
        ALLOWED_RUNNING_READS.put("projectSession", 1);
    }

    /** Non-final statics allowed in console/, stream/, session/ and Game; none is session state. */
    private static final Map<String, String> ALLOWED_STATICS = new TreeMap<>();
    static {
        ALLOWED_STATICS.put("stream/StreamRuntime.java#current",
                "transport registry: the one live transport of the process");
        ALLOWED_STATICS.put("stream/StreamRuntime.java#newestGeneration",
                "transport registry: generation fence for late events");
        ALLOWED_STATICS.put("stream/StreamRuntime.java#newestStop",
                "transport registry: predecessor stop, weak so no owner chain stays");
        ALLOWED_STATICS.put("console/ConsoleActivity.java#initialLibraryPresentation",
                "UI cache: first library frame across recreation");
        ALLOWED_STATICS.put("console/ConsoleEntryShell.java#bloomBitmap", "UI bitmap cache");
        ALLOWED_STATICS.put("console/ConsoleEntryShell.java#vignetteBitmap", "UI bitmap cache");
        ALLOWED_STATICS.put("console/ConsoleIntroView.java#bandCache", "UI bitmap cache");
        ALLOWED_STATICS.put("console/ConsoleIntroView.java#markCache", "UI bitmap cache");
        ALLOWED_STATICS.put("console/ConsoleIntroView.java#moteCache", "UI bitmap cache");
        ALLOWED_STATICS.put("console/ConsoleMenuBackdrop.java#worker",
                "UI: one blur worker thread for every backdrop");
        ALLOWED_STATICS.put("console/ConsoleAudioEngine.java#ambientPositionMs",
                "UI audio: ambient track position carried to the next console");
        ALLOWED_STATICS.put("console/ProfileAuthorization.java#grantedHost",
                "process-scoped profile PIN grant, not session state");
        ALLOWED_STATICS.put("console/ProfileAuthorization.java#listener",
                "listener of that PIN grant");
        // MoonWakerExit (decision 5) keeps only final atomics, so it needs no entry.
    }

    private static final Pattern RUNNING_READ =
            Pattern.compile("\\b(runningGameId|hostRunningAppId)\\b");
    private static final Pattern METHOD = Pattern.compile(
            "^    (?:@\\w+ +)*(?:(?:public|protected|private|static|final|synchronized) +)*"
                    + "(?=[\\w<])[\\w<>\\[\\], .?]+ +(\\w+)\\(", Pattern.MULTILINE);
    private static final Pattern STATIC_FIELD = Pattern.compile(
            "^\\s*(?:(?:private|public|protected|volatile|transient)\\s+)*static\\s+"
                    + "(?:(?:volatile|transient)\\s+)*"
                    + "(?!final\\b|class\\b|interface\\b|enum\\b|void\\b|synchronized\\b|abstract\\b"
                    + "|native\\b|<)[\\w<>\\[\\], .?]+?\\s+(\\w+)\\s*(?:=(?!=)|;)");

    @Test public void activityAndGameReadTheHostsRunningGameOnlyWhereAllowed() {
        assertFalse("Game reads no host-reported running game",
                RUNNING_READ.matcher(main("Game.java")).find());
        assertEquals(ALLOWED_RUNNING_READS, runningReadsByMethod(main("console/ConsoleActivity.java")));
    }

    @Test public void sessionStorePreferencesAreOpenedOnlyInsideSession() throws IOException {
        List<String> names = new ArrayList<>();
        Matcher declared = Pattern.compile("static final String PREFS(?:_NAME)? = \"([^\"]+)\"")
                .matcher("");
        for (Path store : javaFiles(MAIN_JAVA.resolve("session"))) {
            declared.reset(SourceText.read(store));
            while (declared.find()) names.add(declared.group(1));
        }
        assertTrue(names.containsAll(List.of("moonwaker_suspended_sessions",
                "moonwaker_host_sleep_states", "SessionResume")));
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(MAIN_JAVA.getParent().getParent())) {
            if (file.startsWith(MAIN_JAVA.resolve("session"))) continue;
            String[] lines = SourceText.read(file).split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("Log.")) continue; // "SessionResume" is also a log tag
                for (String name : names) {
                    if (lines[i].contains('"' + name + '"')) {
                        violations.add(MAIN_JAVA.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test public void sessionPackagesKeepNoMutableStaticsButTheAllowedOnes() throws IOException {
        List<Path> files = new ArrayList<>();
        files.addAll(javaFiles(MAIN_JAVA.resolve("console")));
        files.addAll(javaFiles(MAIN_JAVA.resolve("stream")));
        files.addAll(javaFiles(MAIN_JAVA.resolve("session")));
        files.add(MAIN_JAVA.resolve("Game.java"));
        Map<String, String> found = new TreeMap<>();
        for (Path file : files) {
            String relative = MAIN_JAVA.relativize(file).toString().replace('\\', '/');
            for (String line : SourceText.read(file).split("\n")) {
                Matcher field = STATIC_FIELD.matcher(line);
                if (field.find() && !line.contains(" final ")) {
                    String key = relative + "#" + field.group(1);
                    found.put(key, ALLOWED_STATICS.getOrDefault(key, "NOT ALLOWED"));
                }
            }
        }
        assertEquals(ALLOWED_STATICS, found);
        // The registry's stop is weak and the stop owner releases its predecessor (K5.3b).
        String runtime = main("stream/StreamRuntime.java");
        String stop = main("stream/TransportStopCoordinator.java");
        assertTrue(runtime.contains("static WeakReference<TransportStopCoordinator> newestStop"));
        assertTrue(stop.contains("private TransportStopCoordinator predecessor"));
        assertTrue(stop.contains("predecessor = null"));
    }

    /** The published session projection is the only running-game source; no Activity copy. */
    @Test public void consoleKeepsNoCopyOfTheSessionProjection() {
        String console = main("console/ConsoleActivity.java");
        assertTrue(console.contains("gateway.snapshot().sessionSnapshot"));
        for (String removed : new String[] {"publishedSessionSnapshots", "getPlayniteCurrentGame(",
                "RunningGameObservation", "activePlayniteGame", "private boolean closePreviousSession("}) {
            assertFalse(removed, console.contains(removed));
        }
        assertFalse(main("Game.java").contains("stopProviderGame("));
    }

    private static Map<String, Integer> runningReadsByMethod(String source) {
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher method = METHOD.matcher(source);
        while (method.find()) {
            starts.add(method.start());
            names.add(method.group(1));
        }
        Map<String, Integer> reads = new TreeMap<>();
        Matcher read = RUNNING_READ.matcher(source);
        while (read.find()) {
            String owner = "<field>";
            for (int i = 0; i < starts.size() && starts.get(i) < read.start(); i++) {
                owner = names.get(i);
            }
            reads.merge(owner, 1, Integer::sum);
        }
        return reads;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted().collect(Collectors.toList());
        }
    }
}
