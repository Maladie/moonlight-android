package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LockableScrollViewTest {
    @Test
    public void communityLockBlocksOnlyTheOuterScrollOwner() {
        assertTrue(LockableScrollView.blocksOuterScroll(true));
        assertFalse(LockableScrollView.blocksOuterScroll(false));
    }
}
