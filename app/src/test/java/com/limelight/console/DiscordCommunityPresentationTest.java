package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.limelight.discord.DiscordSocialClient;

import java.util.Arrays;

public class DiscordCommunityPresentationTest {
    @Test
    public void initialsAreStableWithoutAnAvatar() {
        assertEquals("LM", DiscordCommunityPresentation.initials("Lelumpolelum Mildra"));
        assertEquals("V", DiscordCommunityPresentation.initials("Veinar"));
        assertEquals("?", DiscordCommunityPresentation.initials(""));
    }

    @Test
    public void avatarUrlsAreRestrictedToDiscordHttpsCdns() {
        assertTrue(DiscordAvatarLoader.safeUrl("https://cdn.discordapp.com/avatars/1/a.png"));
        assertTrue(DiscordAvatarLoader.safeUrl("https://media.discordapp.net/avatars/1/a.png"));
        assertFalse(DiscordAvatarLoader.safeUrl("http://cdn.discordapp.com/avatars/1/a.png"));
        assertFalse(DiscordAvatarLoader.safeUrl("https://example.invalid/avatar.png"));
    }

    @Test
    public void communityChannelProjectionKeepsFavoriteOrderAndDeduplicatesRecent() {
        HostGatewayClient.DiscordChannel favorite = new HostGatewayClient.DiscordChannel(
                "one", "guild", "Guild", "Lobby", 2, true);
        HostGatewayClient.DiscordChannel recentDuplicate = new HostGatewayClient.DiscordChannel(
                "one", "guild", "Guild", "Lobby", 2, true);
        HostGatewayClient.DiscordChannel recent = new HostGatewayClient.DiscordChannel(
                "two", "guild", "Guild", "General", 0, false);
        java.util.List<DiscordCommunityPresentation.Destination> destinations =
                DiscordCommunityPresentation.channels(Arrays.asList(favorite),
                        Arrays.asList(recentDuplicate, recent));
        assertEquals(Arrays.asList("discord.community.channel:one", "discord.community.channel:two"),
                Arrays.asList(destinations.get(0).id, destinations.get(1).id));
        assertTrue(destinations.get(0).active);
    }

    @Test
    public void presenceProjectionKeepsOfflineDistinctFromActiveFriends() {
        assertTrue(DiscordCommunityPresentation.isPresent(DiscordSocialClient.Friend.Group.PLAYING));
        assertTrue(DiscordCommunityPresentation.isPresent(DiscordSocialClient.Friend.Group.ONLINE));
        assertFalse(DiscordCommunityPresentation.isPresent(DiscordSocialClient.Friend.Group.OFFLINE));
    }

    @Test
    public void togetherProjectsOnlineFriendsAndActiveChannelsBeforeRecentPlaces() {
        DiscordCommunityPresentation.Destination playing = destination("playing", true);
        DiscordCommunityPresentation.Destination online = destination("online", true);
        DiscordCommunityPresentation.Destination activeChannel = destination("channel-active", true);
        DiscordCommunityPresentation.Destination inactiveChannel = destination("channel-recent", false);
        DiscordCommunityPresentation.Destination guild = destination("guild", false);
        assertEquals(Arrays.asList("playing", "online", "channel-active"), ids(
                DiscordCommunityPresentation.togetherActive(Arrays.asList(playing), Arrays.asList(online),
                        Arrays.asList(activeChannel, inactiveChannel))));
        assertEquals(Arrays.asList("channel-recent", "guild"), ids(
                DiscordCommunityPresentation.togetherRecent(Arrays.asList(activeChannel, inactiveChannel),
                        Arrays.asList(guild))));
    }

    @Test
    public void verifiedVoiceDestinationReplacesRatherThanDuplicatesSavedChannel() {
        DiscordCommunityPresentation.Destination active = destination("discord.community.channel:one", true);
        DiscordCommunityPresentation.Destination saved = destination("discord.community.channel:one", false);
        DiscordCommunityPresentation.Destination other = destination("discord.community.channel:two", false);
        assertEquals(Arrays.asList("discord.community.channel:one", "discord.community.channel:two"), ids(
                DiscordCommunityPresentation.withActiveVoice(active, Arrays.asList(saved, other))));
    }

    @Test
    public void connectedChannelProjectionMatchesOnlyTheVerifiedVoiceChannel() {
        HostGatewayClient.DiscordVoice voice = new HostGatewayClient.DiscordVoice(true, "one", "Lobby",
                "guild", false, false, 0, java.util.Collections.emptyList());
        assertTrue(DiscordCommunityView.isConnectedChannel("discord.community.channel:one", voice));
        assertFalse(DiscordCommunityView.isConnectedChannel("discord.community.channel:two", voice));
    }

    private static DiscordCommunityPresentation.Destination destination(String id, boolean active) {
        return new DiscordCommunityPresentation.Destination(id,
                DiscordCommunityPresentation.Kind.CHANNEL, id, "", "", active, 0, null);
    }

    private static java.util.List<String> ids(
            java.util.List<DiscordCommunityPresentation.Destination> destinations) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (DiscordCommunityPresentation.Destination destination : destinations) ids.add(destination.id);
        return ids;
    }
}
