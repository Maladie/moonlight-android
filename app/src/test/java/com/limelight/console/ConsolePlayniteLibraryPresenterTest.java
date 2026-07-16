package com.limelight.console;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ConsolePlayniteLibraryPresenterTest {
    @Test public void recentGamesComeFirstAndUninstalledCanBeHidden() {
        List<ConsoleDataRepository.App> result = ConsolePlayniteLibraryPresenter.prepare(
                Arrays.asList(game("Alpha", true, "2026-01-01T10:00:00Z"),
                        game("Beta", false, "2026-06-01T10:00:00Z"),
                        game("Gamma", true, "2026-05-01T10:00:00Z")), true);
        assertEquals(2, result.size());
        assertEquals("Gamma", result.get(0).name);
        assertEquals("Alpha", result.get(1).name);
    }

    @Test public void allGamesModeIncludesUninstalledGames() {
        List<ConsoleDataRepository.App> result = ConsolePlayniteLibraryPresenter.prepare(
                Arrays.asList(game("Alpha", true, ""), game("Beta", false, "")), false);
        assertEquals(2, result.size());
    }

    private static ConsoleDataRepository.App game(
            String name, boolean installed, String lastPlayed) {
        return new ConsoleDataRepository.App(name.hashCode(), name, null, false,
                "00000000-0000-0000-0000-" + String.format("%012d", Math.abs(name.hashCode())),
                installed, false, lastPlayed, false);
    }
}
