package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.discord.DiscordSocialClient;
import com.limelight.ui.overlay.OverlayMenuView;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

public class DiscordOverlayControllerTest {
    @Test
    public void firstCommunityOpenCanActivateWithoutASubsequentRailChange() {
        assertTrue(DiscordOverlayController.shouldActivateCommunity(false, false, true));
        assertFalse(DiscordOverlayController.shouldActivateCommunity(true, false, true));
        assertFalse(DiscordOverlayController.shouldActivateCommunity(false, true, true));
        assertFalse(DiscordOverlayController.shouldActivateCommunity(false, false, false));
    }

    @Test
    public void pauseResumeRestoresOnlyAnAlreadyVisibleCommunityShell() {
        assertTrue(DiscordOverlayController.shouldRestoreCommunityAfterPause(true, true, true));
        assertFalse(DiscordOverlayController.shouldRestoreCommunityAfterPause(false, true, true));
        assertFalse(DiscordOverlayController.shouldRestoreCommunityAfterPause(true, false, true));
        assertFalse(DiscordOverlayController.shouldRestoreCommunityAfterPause(true, true, false));
    }

    @Test
    public void pauseLifecycleDependsOnTheViewChatExitContract() throws Exception {
        assertNotNull(OverlayMenuView.class.getMethod("exitDiscordCommunityChatForPause"));
    }

    @Test
    public void friendChatIsExplicitlyUnavailableWhenItCannotOwnTheDmLease() {
        assertFalse(DiscordOverlayController.shouldShowChatUnavailable(true, true));
        assertTrue(DiscordOverlayController.shouldShowChatUnavailable(true, false));
        assertTrue(DiscordOverlayController.shouldShowChatUnavailable(false, true));
    }

    @Test
    public void leaseRetryIsBoundedAndNeverRequiresTakingAnotherOwnersLease() {
        assertTrue(DiscordOverlayController.shouldRetryLease(0, 3));
        assertTrue(DiscordOverlayController.shouldRetryLease(2, 3));
        assertFalse(DiscordOverlayController.shouldRetryLease(3, 3));
        assertFalse(DiscordOverlayController.shouldRetryLease(-1, 3));
    }

    @Test
    public void unchangedProjectionSignatureDoesNotForceAnotherCommunityRender() {
        assertTrue(DiscordOverlayController.shouldRenderCommunitySignature(null, "model-a"));
        assertFalse(DiscordOverlayController.shouldRenderCommunitySignature("model-a", "model-a"));
        assertTrue(DiscordOverlayController.shouldRenderCommunitySignature("model-a", "model-b"));
    }

    @Test
    public void visibleSectionProjectionIgnoresOtherSectionsButKeepsVisibleChanges() {
        OverlayMenuView.CommunityModel quiet = communityModel(false, "one");
        OverlayMenuView.CommunityModel speaking = communityModel(true, "one");
        OverlayMenuView.CommunityModel changedAvatar = communityModel(false, "one", "", false, "", "new-avatar");
        OverlayMenuView.CommunityModel changedChat = communityModel(false, "two");

        assertEquals(DiscordOverlayController.communityPresentationSignature(quiet,
                OverlayMenuView.CommunitySection.FRIENDS, false),
                DiscordOverlayController.communityPresentationSignature(speaking,
                        OverlayMenuView.CommunitySection.FRIENDS, false));
        assertFalse(DiscordOverlayController.communityPresentationSignature(quiet,
                OverlayMenuView.CommunitySection.TOGETHER, false).equals(
                DiscordOverlayController.communityPresentationSignature(speaking,
                        OverlayMenuView.CommunitySection.TOGETHER, false)));
        assertFalse(DiscordOverlayController.communityPresentationSignature(quiet,
                OverlayMenuView.CommunitySection.TOGETHER, false).equals(
                DiscordOverlayController.communityPresentationSignature(changedAvatar,
                        OverlayMenuView.CommunitySection.TOGETHER, false)));
        assertFalse(DiscordOverlayController.communityPresentationSignature(quiet,
                OverlayMenuView.CommunitySection.FRIENDS, true).equals(
                DiscordOverlayController.communityPresentationSignature(changedChat,
                        OverlayMenuView.CommunitySection.FRIENDS, true)));
    }

    @Test
    public void chatSignatureKeepsTypingAndSendStartLocalButIncludesTerminalState() {
        OverlayMenuView.CommunityModel current = communityModel(false, "one", "draft", false, "");
        OverlayMenuView.CommunityModel typed = communityModel(false, "one", "new draft", false, "");
        OverlayMenuView.CommunityModel sending = communityModel(false, "one", "draft", true, "");
        OverlayMenuView.CommunityModel failed = communityModel(false, "one", "draft", false, "failed");

        String signature = DiscordOverlayController.communityPresentationSignature(current,
                OverlayMenuView.CommunitySection.FRIENDS, true);
        assertEquals(signature, DiscordOverlayController.communityPresentationSignature(typed,
                OverlayMenuView.CommunitySection.FRIENDS, true));
        assertEquals(signature, DiscordOverlayController.communityPresentationSignature(sending,
                OverlayMenuView.CommunitySection.FRIENDS, true));
        assertFalse(signature.equals(DiscordOverlayController.communityPresentationSignature(failed,
                OverlayMenuView.CommunitySection.FRIENDS, true)));
    }

    @Test
    public void acceptedSendStartForcesTheSingleSendingProjection() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/DiscordOverlayController.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/console/DiscordOverlayController.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String method = text.substring(text.indexOf("public void onDiscordCommunitySendChat("),
                text.indexOf("public void onDiscordCommunityOpenMessageInDiscord("));

        assertTrue(method.indexOf("directMessages.beginSend")
                < method.indexOf("lastCommunitySignature = null"));
        assertTrue(method.indexOf("lastCommunitySignature = null")
                < method.indexOf("renderCommunity()"));
    }

    @Test
    public void emptySuccessfulHistoryProjectsAnEmptyChatWithoutAnError() {
        OverlayMenuView.ChatModel empty = new OverlayMenuView.ChatModel("1", "Friend", "", "",
                Collections.emptyList(), false, false, "", false, "");
        assertTrue(empty.messages.isEmpty());
        assertFalse(empty.loadingHistory);
        assertEquals("", empty.error);
    }

    @Test
    public void directMessageAuthorizationWaitsForTheActualOverlayCloseCallback() {
        assertFalse(DiscordOverlayController.shouldStartDirectMessageAuthorization(false, true));
        assertFalse(DiscordOverlayController.shouldStartDirectMessageAuthorization(true, false));
        assertTrue(DiscordOverlayController.shouldStartDirectMessageAuthorization(true, true));
    }

    @Test
    public void homeRetryIsOneBoundedAttemptOnlyForAHealthyBridge() {
        HostGatewayClient.DiscordStatus healthy = new HostGatewayClient.DiscordStatus(true, true, true, "");
        assertTrue(DiscordOverlayController.shouldRetryCommunityHome(false, healthy));
        assertFalse(DiscordOverlayController.shouldRetryCommunityHome(true, healthy));
        assertFalse(DiscordOverlayController.shouldRetryCommunityHome(false,
                new HostGatewayClient.DiscordStatus(true, false, true, "")));
        assertFalse(DiscordOverlayController.shouldRetryCommunityHome(false, null));
    }

    @Test
    public void staleHomeAndGuildResultsAreRejectedByConnectionOrGeneration() {
        assertTrue(DiscordOverlayController.acceptsCommunityGeneration(false, false, true, true,
                true, 7, 7));
        assertFalse(DiscordOverlayController.acceptsCommunityGeneration(false, false, true, true,
                true, 7, 8));
        assertFalse(DiscordOverlayController.acceptsCommunityGeneration(false, false, true, true,
                false, 7, 7));
        assertFalse(DiscordOverlayController.acceptsCommunityGeneration(false, false, false, true,
                true, 7, 7));
    }

    @Test
    public void staleGuildOrJoinGenerationCannotApplyAfterLeavingItsDetail() {
        assertTrue(DiscordOverlayController.acceptsGuildGeneration(4, 4, 9, 9));
        assertFalse(DiscordOverlayController.acceptsGuildGeneration(4, 5, 9, 9));
        assertFalse(DiscordOverlayController.acceptsGuildGeneration(4, 4, 9, 10));
    }

    @Test
    public void guildLoadDoesNotFanOutWhileAnExistingRequestIsRunning() {
        assertTrue(DiscordOverlayController.canStartGuildLoad(true, false));
        assertFalse(DiscordOverlayController.canStartGuildLoad(true, true));
        assertFalse(DiscordOverlayController.canStartGuildLoad(false, false));
    }

    @Test
    public void channelSelectionUsesOnlyTheExactKnownId() {
        HostGatewayClient.DiscordChannel first = new HostGatewayClient.DiscordChannel(
                "11111", "22222", "Guild", "First", 1, true);
        HostGatewayClient.DiscordChannel second = new HostGatewayClient.DiscordChannel(
                "33333", "22222", "Guild", "Second", 2, false);
        assertSame(second, DiscordOverlayController.findChannel(Arrays.asList(first, second), "33333"));
        assertNull(DiscordOverlayController.findChannel(Collections.singletonList(first), "33333"));
    }

    @Test
    public void verifiedJoinRequiresTheRequestedConnectedChannel() {
        HostGatewayClient.DiscordVoice matching = new HostGatewayClient.DiscordVoice(
                true, "11111", "First", "22222", false, false, 1);
        assertTrue(DiscordOverlayController.isVerifiedCommunityJoin("11111", matching));
        assertFalse(DiscordOverlayController.isVerifiedCommunityJoin("33333", matching));
        assertFalse(DiscordOverlayController.isVerifiedCommunityJoin("11111",
                new HostGatewayClient.DiscordVoice(false, "11111", "First", "22222", false, false, 0)));
    }

    @Test
    public void verifiedJoinVoiceImmediatelyProjectsAllParticipantFields() {
        HostGatewayClient.DiscordVoice verified = new HostGatewayClient.DiscordVoice(true, "11111", "Voice",
                "22222", true, true, 2, Arrays.asList(
                new HostGatewayClient.DiscordParticipant("1", "Self", 80, true, true, true),
                new HostGatewayClient.DiscordParticipant("2", "Friend", 125, false, false, false)));
        OverlayMenuView.VoiceSummary projected = DiscordOverlayController.projectVerifiedVoice(verified, "Guild");

        assertTrue(projected.connected);
        assertEquals("Guild", projected.guildName);
        assertTrue(projected.muted);
        assertTrue(projected.deafened);
        assertEquals(2, projected.participants.size());
        assertEquals(125, projected.participants.get(1).volume);
        assertTrue(projected.participants.get(0).speaking);
        assertTrue(projected.participants.get(0).self);
    }

    @Test
    public void voiceAvatarUsesOnlyExactDiscordIdsOrTheSelfIdentity() throws IOException {
        assertEquals("self-avatar", DiscordOverlayController.voiceParticipantAvatarUrl("other", true,
                "self", "self-avatar", Collections.emptyList()));
        assertEquals("self-avatar", DiscordOverlayController.voiceParticipantAvatarUrl("self", false,
                "self", "self-avatar", Collections.emptyList()));
        assertEquals("", DiscordOverlayController.voiceParticipantAvatarUrl("unknown", false,
                "self", "self-avatar", Collections.emptyList()));

        Path source = Paths.get("src/main/java/com/limelight/console/DiscordOverlayController.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/console/DiscordOverlayController.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("id.equals(friend.userId)"));
        assertFalse(text.contains("friend.displayName.equals"));
        assertTrue(text.contains("participant.avatarUrl"));
    }

    @Test
    public void reconciliationIsBoundedAfterAJoinOrImmediateLeaveOverride() {
        assertTrue(DiscordOverlayController.shouldRetryVoiceReconciliation(1, 3));
        assertTrue(DiscordOverlayController.shouldRetryVoiceReconciliation(2, 3));
        assertFalse(DiscordOverlayController.shouldRetryVoiceReconciliation(3, 3));
        assertFalse(DiscordOverlayController.shouldRetryVoiceReconciliation(0, 3));
    }

    @Test
    public void immediateLeaveOverrideShowsRejoinDespiteAStaleLegacyConnection() {
        HostGatewayClient.DiscordVoice disconnected = new HostGatewayClient.DiscordVoice(false,
                "", "", "", false, false, 0);
        HostGatewayClient.DiscordVoice joined = new HostGatewayClient.DiscordVoice(true,
                "11111", "Voice", "22222", false, false, 1);

        assertFalse(DiscordOverlayController.effectiveVoiceConnected(disconnected, null));
        assertTrue(DiscordOverlayController.effectiveVoiceConnected(joined, null));
    }

    @Test
    public void joinAndLeaveForceRefreshSourceContract() throws IOException {
        Path source = Paths.get("src/main/java/com/limelight/console/DiscordOverlayController.java");
        if (!Files.exists(source)) source = Paths.get(
                "app/src/main/java/com/limelight/console/DiscordOverlayController.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String join = text.substring(text.indexOf("public void onDiscordCommunityJoinChannel("),
                text.indexOf("public void onDiscordCommunityOpenFriendChat("));
        String leave = text.substring(text.indexOf("public void leave()"), text.indexOf("public void rejoin()"));

        assertTrue(join.contains("communityVoiceOverride = finalVerified"));
        assertTrue(join.contains("expectVoice(true, channel.id)"));
        assertTrue(join.contains("refresh(true)"));
        assertTrue(leave.contains("new HostGatewayClient.DiscordVoice(false"));
        assertTrue(leave.contains("expectVoice(false, \"\")"));
        assertTrue(text.contains("if (force) pendingForceRefresh = true"));
        assertTrue(text.contains("if (pendingForceRefresh)"));
        assertTrue(text.contains("refresh(true);"));
        assertTrue(text.contains("effectiveVoiceConnected(communityVoiceOverride, voice)"));
    }

    @Test
    public void allSocialGroupsHaveStableCommunityPresenceMappings() {
        assertEquals(OverlayMenuView.FriendPresence.PLAYING,
                DiscordOverlayController.friendPresence(DiscordSocialClient.Friend.Group.PLAYING));
        assertEquals(OverlayMenuView.FriendPresence.ONLINE,
                DiscordOverlayController.friendPresence(DiscordSocialClient.Friend.Group.ONLINE));
        assertEquals(OverlayMenuView.FriendPresence.OFFLINE,
                DiscordOverlayController.friendPresence(DiscordSocialClient.Friend.Group.OFFLINE));
    }

    @Test
    public void sendResultOnlyClearsDraftOnSuccess() {
        assertEquals("", DiscordOverlayController.directMessageDraftAfterSendResult("draft", true));
        assertEquals("draft", DiscordOverlayController.directMessageDraftAfterSendResult("draft", false));
    }

    @Test
    public void lateSendResultCannotEraseANewerDraft() {
        assertTrue(DiscordOverlayController.shouldClearDraftAfterSendResult(true, true, "sent", "sent"));
        assertFalse(DiscordOverlayController.shouldClearDraftAfterSendResult(false, true, "sent", "sent"));
        assertFalse(DiscordOverlayController.shouldClearDraftAfterSendResult(true, false, "sent", "sent"));
        assertFalse(DiscordOverlayController.shouldClearDraftAfterSendResult(true, true, "newer", "sent"));
    }

    @Test
    public void historyAndOpenMessageFailuresHaveDedicatedSafeFeedbackPaths() {
        assertTrue(DiscordOverlayController.shouldRenderHistoryFailure(true, false));
        assertFalse(DiscordOverlayController.shouldRenderHistoryFailure(false, false));
        assertFalse(DiscordOverlayController.shouldRenderHistoryFailure(true, true));
        assertTrue(DiscordOverlayController.shouldShowOpenMessageFailure(false));
        assertFalse(DiscordOverlayController.shouldShowOpenMessageFailure(true));
    }

    private static OverlayMenuView.CommunityModel communityModel(boolean speaking, String message) {
        return communityModel(speaking, message, "", false, "");
    }

    private static OverlayMenuView.CommunityModel communityModel(boolean speaking, String message,
                                                                   String draft, boolean sending, String error) {
        return communityModel(speaking, message, draft, sending, error, "avatar");
    }

    private static OverlayMenuView.CommunityModel communityModel(boolean speaking, String message,
                                                                   String draft, boolean sending, String error,
                                                                   String voiceAvatarUrl) {
        return new OverlayMenuView.CommunityModel(1, OverlayMenuView.CommunityStatus.READY, "",
                OverlayMenuView.CommunityStatus.READY, "",
                new OverlayMenuView.VoiceSummary(true, "Guild", "Voice", false, false,
                        Collections.singletonList(new OverlayMenuView.VoiceParticipant("self", "Me", 100,
                                false, speaking, true, voiceAvatarUrl))),
                Collections.singletonList(new OverlayMenuView.CommunityFriend("1", "Friend", "", "",
                        OverlayMenuView.FriendPresence.ONLINE)), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), "", true,
                new OverlayMenuView.ChatModel("1", "Friend", "", draft,
                        Collections.singletonList(new OverlayMenuView.ChatMessage("m", message, "", "", 0,
                                false, false)), false, sending, error, false, ""));
    }
}
