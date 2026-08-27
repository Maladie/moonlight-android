package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.discord.DiscordSocialClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only, backend-neutral projection used by the Community surface. */
public final class DiscordCommunityPresentation {
    enum Kind { FRIEND, CHANNEL, SERVER, ACTIVE_VOICE }

    static final class Destination {
        final String id;
        final Kind kind;
        final String title;
        final String subtitle;
        final String initials;
        final String avatarUrl;
        final boolean active;
        final int participantCount;
        final Object source;
        final boolean unread;

        Destination(String id, Kind kind, String title, String subtitle, String avatarUrl,
                    boolean active, int participantCount, Object source) {
            this(id, kind, title, subtitle, avatarUrl, active, participantCount, source, false);
        }

        Destination(String id, Kind kind, String title, String subtitle, String avatarUrl,
                    boolean active, int participantCount, Object source, boolean unread) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
            this.initials = initials(title);
            this.avatarUrl = avatarUrl;
            this.active = active;
            this.participantCount = participantCount;
            this.source = source;
            this.unread = unread;
        }

        Destination withUnread(boolean value) {
            return unread == value ? this : new Destination(id, kind, title, subtitle, avatarUrl,
                    active, participantCount, source, value);
        }
    }

    private DiscordCommunityPresentation() { }

    static List<Destination> friends(List<DiscordSocialClient.Friend> friends,
                                     DiscordSocialClient.Friend.Group group,
                                     String idleSubtitle) {
        List<Destination> result = new ArrayList<>();
        for (DiscordSocialClient.Friend friend : friends) {
            if (friend.group != group) continue;
            String subtitle = friend.activityName.isEmpty() ? idleSubtitle : friend.activityName;
            result.add(new Destination("discord.social.friend:" + friend.userId, Kind.FRIEND,
                    friend.displayName, subtitle, friend.avatarUrl, isPresent(friend.group), 0, friend));
        }
        return result;
    }

    static List<Destination> channels(List<HostGatewayClient.DiscordChannel> favorites,
                                      List<HostGatewayClient.DiscordChannel> recent) {
        List<Destination> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addChannels(result, seen, favorites);
        addChannels(result, seen, recent);
        return result;
    }

    static List<Destination> channels(List<HostGatewayClient.DiscordChannel> channels) {
        List<Destination> result = new ArrayList<>();
        addChannels(result, new LinkedHashSet<>(), channels);
        return result;
    }

    static List<Destination> guilds(List<HostGatewayClient.DiscordGuild> guilds) {
        List<Destination> result = new ArrayList<>();
        for (HostGatewayClient.DiscordGuild guild : guilds) {
            result.add(new Destination("discord.community.guild:" + guild.id, Kind.SERVER,
                    guild.name, "", "", false, 0, guild));
        }
        return result;
    }

    static List<Destination> togetherActive(List<Destination> playing, List<Destination> online,
                                             List<Destination> channels) {
        List<Destination> result = new ArrayList<>();
        result.addAll(playing);
        result.addAll(online);
        for (Destination channel : channels) if (channel.active) result.add(channel);
        return result;
    }

    static List<Destination> togetherRecent(List<Destination> channels,
                                             List<Destination> guilds) {
        List<Destination> result = new ArrayList<>();
        for (Destination channel : channels) if (!channel.active) result.add(channel);
        result.addAll(guilds);
        return result;
    }

    static List<Destination> withActiveVoice(Destination activeVoice, List<Destination> channels) {
        List<Destination> result = new ArrayList<>();
        if (activeVoice != null) result.add(activeVoice);
        for (Destination channel : channels) {
            if (activeVoice == null || !activeVoice.id.equals(channel.id)) result.add(channel);
        }
        return result;
    }

    private static void addChannels(List<Destination> result, Set<String> seen,
                                    List<HostGatewayClient.DiscordChannel> channels) {
        for (HostGatewayClient.DiscordChannel channel : channels) {
            if (!seen.add(channel.id)) continue;
            result.add(new Destination("discord.community.channel:" + channel.id, Kind.CHANNEL,
                    "# " + channel.name, channel.guildName, "", channel.people > 0,
                    channel.people, channel));
        }
    }

    static boolean isPresent(DiscordSocialClient.Friend.Group group) {
        return group != DiscordSocialClient.Friend.Group.OFFLINE;
    }

    public static String initials(String value) {
        if (value == null || value.trim().isEmpty()) return "?";
        String trimmed = value.trim();
        String[] words = trimmed.split("\\s+");
        String first = words[0].substring(0, 1).toUpperCase();
        if (words.length == 1) return first;
        return first + words[words.length - 1].substring(0, 1).toUpperCase();
    }

    public static FrameLayout avatar(Context context, String title, String avatarUrl, int sizeDp) {
        int size = Math.round(sizeDp * context.getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xFF5767C9);
        frame.setBackground(shape);
        TextView initials = new TextView(context);
        initials.setText(initials(title));
        initials.setTextColor(Color.WHITE);
        initials.setTextSize(12);
        initials.setGravity(Gravity.CENTER);
        frame.addView(initials, new FrameLayout.LayoutParams(size, size));
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable imageMask = new GradientDrawable();
        imageMask.setShape(GradientDrawable.OVAL);
        imageMask.setColor(Color.TRANSPARENT);
        image.setBackground(imageMask);
        image.setClipToOutline(true);
        frame.addView(image, new FrameLayout.LayoutParams(size, size));
        DiscordAvatarLoader.load(avatarUrl, image);
        return frame;
    }
}
