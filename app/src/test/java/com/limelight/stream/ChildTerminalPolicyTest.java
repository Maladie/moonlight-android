package com.limelight.stream;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ChildTerminalPolicyTest {
    @Test public void gameStoppedRetainsButBudgetAndUncertainCleanupTerminate() {
        assertEquals(ChildTerminalPolicy.Action.RETAIN_GAME_ONLY,
                ChildTerminalPolicy.action("game_stopped", false));
        assertEquals(ChildTerminalPolicy.Action.TERMINATE_SESSION,
                ChildTerminalPolicy.action("time_expired", false));
        assertEquals(ChildTerminalPolicy.Action.TERMINATE_SESSION,
                ChildTerminalPolicy.action("game_stopped", true));
    }

    @Test public void ownerSelectsExactlyOneTerminalEffect() {
        List<String> effects = new ArrayList<>();
        ChildTerminalPolicy.apply("game_stopped", false, true,
                () -> effects.add("retain"), () -> effects.add("terminate"));
        ChildTerminalPolicy.apply("game_stopped", false, false,
                () -> effects.add("retain"), () -> effects.add("terminate"));
        assertEquals(List.of("retain", "terminate"), effects);
    }
}
