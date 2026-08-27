package com.limelight.console;

import android.view.KeyEvent;

/** Small ephemeral navigation state for the dedicated Community surface. */
final class DiscordCommunityState {
    enum Tab { TOGETHER, FRIENDS, SERVERS }
    enum Detail { FEED, FRIEND, DIRECT_MESSAGE, SERVER_CHANNELS, CHANNEL, OPTIONS, AUDIO, SOCIAL }

    final Tab tab;
    final Detail detail;
    final String selectedId;
    final String parentDestinationId;
    final Detail returnDetail;
    final long directMessageRecipientId;
    final long directMessageGeneration;

    DiscordCommunityState(Tab tab, Detail detail, String selectedId, String parentDestinationId) {
        this(tab, detail, selectedId, parentDestinationId, Detail.FEED);
    }

    DiscordCommunityState(Tab tab, Detail detail, String selectedId, String parentDestinationId,
                          Detail returnDetail) {
        this(tab, detail, selectedId, parentDestinationId, returnDetail, 0, 0);
    }

    DiscordCommunityState(Tab tab, Detail detail, String selectedId, String parentDestinationId,
                          Detail returnDetail, long directMessageRecipientId,
                          long directMessageGeneration) {
        this.tab = tab;
        this.detail = detail;
        this.selectedId = selectedId == null ? "" : selectedId;
        this.parentDestinationId = parentDestinationId == null ? "" : parentDestinationId;
        this.returnDetail = returnDetail == null ? Detail.FEED : returnDetail;
        this.directMessageRecipientId = directMessageRecipientId;
        this.directMessageGeneration = directMessageGeneration;
    }

    static DiscordCommunityState initial() { return new DiscordCommunityState(Tab.TOGETHER, Detail.FEED, "", ""); }
    DiscordCommunityState select(String id) { return new DiscordCommunityState(tab, detail, id, parentDestinationId,
            returnDetail, directMessageRecipientId, directMessageGeneration); }
    DiscordCommunityState tab(Tab value) { return new DiscordCommunityState(value, Detail.FEED, selectedId, ""); }
    DiscordCommunityState enter(Detail value) { return new DiscordCommunityState(tab, value, selectedId, parentDestinationId); }
    DiscordCommunityState openServer(String id) {
        return new DiscordCommunityState(tab, Detail.SERVER_CHANNELS, id, id);
    }
    DiscordCommunityState openChannel(String id) {
        return new DiscordCommunityState(tab, Detail.CHANNEL, id, parentDestinationId);
    }
    DiscordCommunityState openOptions() {
        return new DiscordCommunityState(tab, Detail.OPTIONS, selectedId, parentDestinationId, detail);
    }
    DiscordCommunityState openAudio() {
        return new DiscordCommunityState(tab, Detail.AUDIO, selectedId, parentDestinationId,
                Detail.OPTIONS);
    }
    DiscordCommunityState openSocial() {
        return new DiscordCommunityState(tab, Detail.SOCIAL, selectedId, parentDestinationId,
                Detail.OPTIONS);
    }
    DiscordCommunityState openDirectMessage(long recipientId, long generation) {
        return new DiscordCommunityState(tab, Detail.DIRECT_MESSAGE, selectedId, parentDestinationId,
                Detail.FRIEND, recipientId, generation);
    }
    DiscordCommunityState back() {
        if (detail == Detail.DIRECT_MESSAGE) {
            return new DiscordCommunityState(tab, Detail.FRIEND, selectedId, parentDestinationId,
                    Detail.FRIEND, 0, 0);
        }
        if (detail == Detail.OPTIONS || detail == Detail.AUDIO || detail == Detail.SOCIAL) {
            return new DiscordCommunityState(tab, returnDetail, selectedId, parentDestinationId);
        }
        if (detail == Detail.CHANNEL && !parentDestinationId.isEmpty()) {
            return new DiscordCommunityState(tab, Detail.SERVER_CHANNELS, selectedId,
                    parentDestinationId);
        }
        if (detail == Detail.SERVER_CHANNELS && !parentDestinationId.isEmpty()) {
            return new DiscordCommunityState(tab, Detail.FEED, parentDestinationId, "");
        }
        return detail == Detail.FEED ? this : new DiscordCommunityState(tab, Detail.FEED, selectedId, "");
    }

    static int adjacentTabIndex(int index, int keyCode, int count) {
        if (count <= 0) return index;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            return Math.max(0, index - 1);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            return Math.min(count - 1, index + 1);
        }
        return index;
    }
}
