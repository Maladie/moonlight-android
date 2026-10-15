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
        // Display: "End game" entry needs the item's exact process proof.
        ALLOWED_RUNNING_READS.put("isExactRunningManagedGame", 1);
        // Display: library re-render signature.
        ALLOWED_RUNNING_READS.put("sessionPresentationKey", 1);
        // The warm-up's GENERIC vs GAME_CONNECTION is SessionOrchestrator.warmUpTransition's.
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
                "client poll gate; host enforces PIN/Bearer");
        ALLOWED_STATICS.put("console/ProfileAuthorization.java#listener",
                "listener of that poll gate");
    }

    /** Final statics of mutable types in the same packages; none is session state. */
    private static final Map<String, String> ALLOWED_MUTABLE_FINAL_STATICS = new TreeMap<>();
    static {
        String table = "constant table, never written";
        for (String key : new String[] {"Game.java#RETRY_ACTIONS",
                "console/ChildSessionCountdown.java#WARNING_MINUTES",
                "console/ConsoleActivity.java#HOST_KEYED_PREFERENCES",
                "console/ConsoleChildProfileEditor.java#AVATAR_LABELS",
                "console/ConsoleChildProfileEditor.java#AVATAR_SYMBOLS",
                "console/ConsoleChildProfileEditor.java#AVATAR_VALUES",
                "console/ConsoleChildProfileEditor.java#DAY_LABELS",
                "console/ConsoleEntryShell.java#SPOTS", "console/ConsoleIntroView.java#BOKEH",
                "console/ConsoleMenuBackdrop.java#GLASS_MATRIX",
                "console/ConsolePinEntryView.java#GLASS", "console/ConsolePinEntryView.java#KEYPAD",
                "console/ConsoleProfileOptionsMenu.java#GLASS",
                "console/ConsoleStreamSettings.java#TICK_RESOLUTIONS",
                "console/EmbeddedTvKeyboardModel.java#CURATED_ACCENTS",
                "stream/StreamingAutopilot.java#FRAME_RATES",
                "stream/StreamingAutopilot.java#RESOLUTIONS"}) {
            ALLOWED_MUTABLE_FINAL_STATICS.put(key, table);
        }
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/ConsoleCardFocus.java#MASKS",
                "UI bitmap cache: focus masks per size");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/ConsoleActivity.java#CHILD_POLICY_TIME_FORMATS",
                "parse formats, used under their own lock; no session fact");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/ConsoleActivity.java#playniteBitmapDecodeLocks",
                "lock: one artwork decode per file");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/PlayniteArtworkCache.java#FETCH_LOCKS",
                "lock: one artwork fetch per file");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/ConsoleActivity.java#resolvedPlayniteArtwork",
                "UI artwork cache across recreation");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/CardBackdropBlur.java#blurred",
                "UI bitmap cache, weak keys");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/HostTileArt.java#CACHE", "UI bitmap cache");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/DiscordAvatarLoader.java#IN_FLIGHT",
                "UI: avatar requests in flight, joined per URL");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/ConsoleUiFeedback.java#INSTANCES",
                "UI: one toast surface per Activity, weak keys");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/PlayniteLibraryCache.java#MEMORY",
                "library cache across recreation, not session state");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/MoonWakerExit.java#issued",
                "decision 5: process exit request, not session state");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/MoonWakerExit.java#pending",
                "decision 5: process exit request, not session state");
        ALLOWED_MUTABLE_FINAL_STATICS.put("console/transition/LaunchTransitionSpec.java#SEQUENCE",
                "uniqueness counter for transition ids");
    }

    /** Running-game reads; ConsoleActivity also counts reads of the projection's process proof. */
    private static final Pattern RUNNING_READ =
            Pattern.compile("\\b(runningGameId|hostRunningAppId|verifiedRunningGameId)\\b");
    private static final Pattern ACTIVITY_RUNNING_READ = Pattern.compile(
            "\\b(runningGameId|hostRunningAppId|verifiedRunningGameId|processEvidence)\\b");
    private static final Pattern METHOD = Pattern.compile(
            "^    (?:@\\w+ +)*(?:(?:public|protected|private|static|final|synchronized) +)*"
                    + "(?=[\\w<])[\\w<>\\[\\], .?]+ +(\\w+)\\(", Pattern.MULTILINE);
    /** A field declaration from its annotations to its name, across lines: modifiers, type, name. */
    private static final Pattern FIELD = Pattern.compile(
            "^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "((?:(?:public|protected|private|static|final|volatile|transient)\\s+)+)"
                    + "([^;=(){}@]+?)\\s+(\\w+)\\s*(?:=|;)", Pattern.MULTILINE);
    private static final Pattern MUTABLE_TYPE = Pattern.compile(
            "(?<!\\w)(?:Atomic\\w*|\\w*(?:Map|List|Set|Collection|Queue|Deque))\\b|\\[\\]");

    @Test public void activityAndGameReadTheHostsRunningGameOnlyWhereAllowed() {
        assertFalse("Game reads no host-reported running game",
                RUNNING_READ.matcher(main("Game.java")).find());
        assertEquals(ALLOWED_RUNNING_READS, runningReadsByMethod(main("console/ConsoleActivity.java")));
    }

    @Test public void fieldParserReadsSplitAndAnnotatedDeclarations() {
        Map<String, String> found = new TreeMap<>();
        collectStatics("x", "class X {\n    @Nullable\n    private static\n"
                + "            Foo split;\n    @SuppressWarnings(\"unused\") static final\n"
                + "        Map<String, List<Integer>> table = new HashMap<>();\n"
                + "    private static final int[] codes = {1};\n"
                + "    static final String TAG = \"x\";\n    private final List<String> own;\n"
                + "    static void method(int a) { }\n}\n", found, found);
        assertEquals(Map.of("x#split", "NOT ALLOWED", "x#table", "NOT ALLOWED",
                "x#codes", "NOT ALLOWED"), found);
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
        // "SessionResume" is also a log tag: only a literal that is a Log call's tag is exempt.
        Pattern literal = Pattern.compile("(?<!Log\\.[a-z]\\()\"(?:"
                + names.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")\"");
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(MAIN_JAVA.getParent().getParent())) {
            if (file.startsWith(MAIN_JAVA.resolve("session"))) continue;
            String source = SourceText.read(file);
            Matcher use = literal.matcher(source);
            while (use.find()) {
                long line = source.substring(0, use.start()).chars().filter(c -> c == '\n').count();
                violations.add(MAIN_JAVA.relativize(file) + ":" + (line + 1));
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
        Map<String, String> mutable = new TreeMap<>();
        Map<String, String> mutableFinal = new TreeMap<>();
        for (Path file : files) {
            collectStatics(MAIN_JAVA.relativize(file).toString().replace('\\', '/'),
                    SourceText.read(file), mutable, mutableFinal);
        }
        assertEquals(ALLOWED_STATICS, mutable);
        assertEquals(ALLOWED_MUTABLE_FINAL_STATICS, mutableFinal);
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

    /** Non-final statics go to {@code mutable}; final statics of mutable types to the other. */
    private static void collectStatics(String relative, String source,
                                       Map<String, String> mutable,
                                       Map<String, String> mutableFinal) {
        Matcher field = FIELD.matcher(source);
        while (field.find()) {
            List<String> modifiers = List.of(field.group(1).trim().split("\\s+"));
            if (!modifiers.contains("static")) continue;
            String key = relative + "#" + field.group(3);
            if (!modifiers.contains("final")) {
                mutable.put(key, ALLOWED_STATICS.getOrDefault(key, "NOT ALLOWED"));
            } else if (MUTABLE_TYPE.matcher(field.group(2)).find()) {
                mutableFinal.put(key,
                        ALLOWED_MUTABLE_FINAL_STATICS.getOrDefault(key, "NOT ALLOWED"));
            }
        }
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
        Matcher read = ACTIVITY_RUNNING_READ.matcher(source);
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
