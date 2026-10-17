#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <deque>
#include <memory>
#include <string>
#include <vector>

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

namespace {
constexpr char kProtocolVersion[] = "2";
std::unique_ptr<discordpp::Client> client;
std::string state = "Not connected";
bool connected = false;
bool snapshotDirty = true;
std::vector<std::string> lastSnapshot;

// Direct messages deliberately do not extend the v2 account/friends snapshot. Each element of
// this queue is a versioned, length-prefixed record whose fields are base64 encoded UTF-8. That
// keeps user content safe from newlines, delimiters, and Unicode parsing ambiguity at the JNI
// boundary. The Java side retains these values only in its bounded in-memory chat model.
constexpr size_t kMaxMessageEvents = 256;
struct MessageEvent {
    std::string type;
    std::string messageId;
    std::string record;
};
std::deque<MessageEvent> messageEvents;

std::string base64(const std::string& input) {
    static const char alphabet[] =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((input.size() + 2) / 3) * 4);
    for (size_t i = 0; i < input.size(); i += 3) {
        uint32_t value = static_cast<unsigned char>(input[i]) << 16;
        bool two = i + 1 < input.size();
        bool three = i + 2 < input.size();
        if (two) value |= static_cast<unsigned char>(input[i + 1]) << 8;
        if (three) value |= static_cast<unsigned char>(input[i + 2]);
        out.push_back(alphabet[(value >> 18) & 63]);
        out.push_back(alphabet[(value >> 12) & 63]);
        out.push_back(two ? alphabet[(value >> 6) & 63] : '=');
        out.push_back(three ? alphabet[value & 63] : '=');
    }
    return out;
}

std::string encodedEvent(const std::vector<std::string>& fields) {
    std::string record;
    for (const std::string& field : fields) {
        std::string value = base64(field);
        record += std::to_string(value.size());
        record.push_back(':');
        record += value;
    }
    return record;
}

void queueMessageEvent(const std::string& type, const std::string& messageId,
                       const std::vector<std::string>& fields) {
    if ((type == "UPDATED" || type == "DELETED") && !messageId.empty()) {
        messageEvents.erase(std::remove_if(messageEvents.begin(), messageEvents.end(),
                [&type, &messageId](const MessageEvent& pending) {
                    return pending.messageId == messageId && (pending.type == type
                            || (type == "DELETED" && pending.type == "UPDATED"));
                }), messageEvents.end());
    }
    if (messageEvents.size() >= kMaxMessageEvents) {
        // Never leave Java with a plausible but stale chat model after dropping events.
        messageEvents.clear();
        messageEvents.push_back({"OVERFLOW", "", encodedEvent({"1", "OVERFLOW"})});
        return;
    }
    messageEvents.push_back({type, messageId, encodedEvent(fields)});
}

std::string messageAdditionalType(const discordpp::MessageHandle& message) {
    auto additional = message.AdditionalContent();
    return additional ? discordpp::AdditionalContent::TypeToString(additional->Type()) : "";
}

std::string messageAdditionalCount(const discordpp::MessageHandle& message) {
    auto additional = message.AdditionalContent();
    return additional ? std::to_string(additional->Count()) : "0";
}

std::string messageAdditionalTitle(const discordpp::MessageHandle& message) {
    auto additional = message.AdditionalContent();
    if (!additional) return "";
    auto title = additional->Title();
    return title ? *title : "";
}

void queueMessage(const std::string& type, uint64_t recipientId,
                  const discordpp::MessageHandle& message, uint64_t requestId = 0) {
    if (!message) return;
    if (recipientId == 0 && client) {
        auto currentUser = client->GetCurrentUserV2();
        const uint64_t currentUserId = currentUser ? currentUser->Id() : 0;
        // RecipientId is the local user on inbound DMs. The conversation key must always be the
        // other participant so local unread state is projected onto the correct friend.
        recipientId = message.AuthorId() == currentUserId ? message.RecipientId() : message.AuthorId();
    }
    const std::string messageId = std::to_string(message.Id());
    queueMessageEvent(type, messageId, {"1", type, std::to_string(recipientId),
            std::to_string(requestId), messageId, std::to_string(message.AuthorId()), message.Content(),
            std::to_string(message.SentTimestamp()), std::to_string(message.EditedTimestamp()),
            messageAdditionalType(message), messageAdditionalTitle(message), messageAdditionalCount(message),
            message.DisclosureType() ? "1" : "0"});
}

void queueOpenMessageResult(uint64_t messageId, const discordpp::ClientResult& result) {
    queueMessageEvent("OPEN_MESSAGE_RESULT", "", {"1", "OPEN_MESSAGE_RESULT",
            std::to_string(messageId), result.Successful() ? "1" : "0",
            discordpp::EnumToString(result.Type())});
}

void queueRequestResult(const std::string& type, uint64_t recipientId, uint64_t requestId,
                        const discordpp::ClientResult& result) {
    queueMessageEvent(type, "", {"1", type, std::to_string(recipientId),
            std::to_string(requestId), result.Successful() ? "1" : "0",
            result.Retryable() ? "1" : "0", std::to_string(result.RetryAfter()),
            discordpp::EnumToString(result.Type())});
}

void appendUtf8(std::string* out, uint32_t codePoint) {
    if (codePoint <= 0x7f) {
        out->push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7ff) {
        out->push_back(static_cast<char>(0xc0 | (codePoint >> 6)));
        out->push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    } else if (codePoint <= 0xffff) {
        out->push_back(static_cast<char>(0xe0 | (codePoint >> 12)));
        out->push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
        out->push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    } else {
        out->push_back(static_cast<char>(0xf0 | (codePoint >> 18)));
        out->push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3f)));
        out->push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
        out->push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    }
}

bool jstringToUtf8(JNIEnv* env, jstring input, std::string* output, int* codePointCount) {
    const jsize length = env->GetStringLength(input);
    const jchar* chars = env->GetStringChars(input, nullptr);
    if (!chars) return false;
    output->clear();
    output->reserve(static_cast<size_t>(length));
    *codePointCount = 0;
    for (jsize index = 0; index < length; ++index) {
        uint32_t codePoint = chars[index];
        if (codePoint >= 0xd800 && codePoint <= 0xdbff) {
            if (index + 1 < length && chars[index + 1] >= 0xdc00 && chars[index + 1] <= 0xdfff) {
                codePoint = 0x10000 + ((codePoint - 0xd800) << 10) + (chars[++index] - 0xdc00);
            } else {
                codePoint = 0xfffd;
            }
        } else if (codePoint >= 0xdc00 && codePoint <= 0xdfff) {
            codePoint = 0xfffd;
        }
        appendUtf8(output, codePoint);
        ++*codePointCount;
    }
    env->ReleaseStringChars(input, chars);
    return true;
}

void markDirty() {
    snapshotDirty = true;
}

void setState(const std::string& value) {
    state = value;
    markDirty();
}

void append(std::vector<std::string>* values, const std::string& value) {
    values->push_back(value);
}

void refreshSnapshot(std::vector<std::string>* values) {
    std::string userId;
    std::string displayName;
    std::string avatarUrl;
    if (client) {
        auto user = client->GetCurrentUserV2();
        if (user) {
            userId = std::to_string(user->Id());
            displayName = user->DisplayName();
            avatarUrl = user->AvatarUrl(discordpp::UserHandle::AvatarType::Png,
                                        discordpp::UserHandle::AvatarType::Png);
        }
    }

    append(values, kProtocolVersion);
    append(values, state);
    append(values, connected ? "1" : "0");
    append(values, userId);
    append(values, displayName);
    append(values, avatarUrl);

    std::vector<std::string> friends;
    if (client) {
        const discordpp::RelationshipGroupType groups[] = {
                discordpp::RelationshipGroupType::OnlinePlayingGame,
                discordpp::RelationshipGroupType::OnlineElsewhere,
                discordpp::RelationshipGroupType::Offline,
        };
        const char* groupNames[] = {"PLAYING", "ONLINE", "OFFLINE"};
        for (size_t groupIndex = 0; groupIndex < 3; ++groupIndex) {
            for (auto& relationship : client->GetRelationshipsByGroup(groups[groupIndex])) {
                if (relationship.DiscordRelationshipType() != discordpp::RelationshipType::Friend) continue;
                auto user = relationship.User();
                if (!user) continue;
                std::string activity;
                auto gameActivity = user->GameActivity();
                if (gameActivity) activity = gameActivity->Name();
                friends.push_back(std::to_string(user->Id()));
                friends.push_back(user->DisplayName());
                friends.emplace_back(groupNames[groupIndex]);
                friends.push_back(activity);
                friends.push_back(user->AvatarUrl(discordpp::UserHandle::AvatarType::Png,
                                                   discordpp::UserHandle::AvatarType::Png));
            }
        }
    }
    append(values, std::to_string(friends.size() / 5));
    values->insert(values->end(), friends.begin(), friends.end());
    snapshotDirty = false;
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    for (size_t index = 0; index < value.size();) {
        const uint8_t lead = static_cast<uint8_t>(value[index]);
        uint32_t codePoint = 0xfffd;
        uint32_t minimum = 0;
        size_t length = 1;
        if (lead < 0x80) {
            codePoint = lead;
        } else if ((lead & 0xe0) == 0xc0) {
            codePoint = lead & 0x1f;
            minimum = 0x80;
            length = 2;
        } else if ((lead & 0xf0) == 0xe0) {
            codePoint = lead & 0x0f;
            minimum = 0x800;
            length = 3;
        } else if ((lead & 0xf8) == 0xf0) {
            codePoint = lead & 0x07;
            minimum = 0x10000;
            length = 4;
        }
        bool valid = length > 1 && index + length <= value.size();
        for (size_t offset = 1; valid && offset < length; ++offset) {
            const uint8_t continuation = static_cast<uint8_t>(value[index + offset]);
            valid = (continuation & 0xc0) == 0x80;
            if (valid) codePoint = (codePoint << 6) | (continuation & 0x3f);
        }
        if (length == 1) valid = lead < 0x80;
        if (!valid || codePoint < minimum || codePoint > 0x10ffff
                || (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
            codePoint = 0xfffd;
            length = 1;
        }
        if (codePoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codePoint));
        } else {
            codePoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xd800 + (codePoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xdc00 + (codePoint & 0x3ff)));
        }
        index += length;
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(),
                          static_cast<jsize>(utf16.size()));
}

jobjectArray toJavaStrings(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (!result) return nullptr;
    for (size_t i = 0; i < values.size(); ++i) {
        jstring value = toJavaString(env, values[i]);
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return result;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeStart(JNIEnv*, jclass, jlong applicationId) {
    if (client) return;
    client = std::make_unique<discordpp::Client>();
    client->SetApplicationId(static_cast<uint64_t>(applicationId));
    client->SetStatusChangedCallback([](auto status, auto error, int32_t detail) {
        connected = status == discordpp::Client::Status::Ready;
        setState(discordpp::Client::StatusToString(status));
        if (error != discordpp::Client::Error::None) {
            setState(discordpp::Client::ErrorToString(error) + " (" + std::to_string(detail) + ")");
        }
    });
    client->SetUserUpdatedCallback([](uint64_t) { markDirty(); });
    client->SetRelationshipGroupsUpdatedCallback([](uint64_t) { markDirty(); });
    client->SetMessageCreatedCallback([](uint64_t messageId) {
        if (!client) return;
        auto message = client->GetMessageHandle(messageId);
        if (message) queueMessage("CREATED", 0, *message);
    });
    client->SetMessageUpdatedCallback([](uint64_t messageId) {
        if (!client) return;
        auto message = client->GetMessageHandle(messageId);
        if (message) queueMessage("UPDATED", 0, *message);
    });
    client->SetMessageDeletedCallback([](uint64_t messageId, uint64_t channelId) {
        queueMessageEvent("DELETED", std::to_string(messageId), {"1", "DELETED",
                std::to_string(messageId), std::to_string(channelId)});
    });
    setState("Not connected");
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeSetStatus(
        JNIEnv* env, jclass, jstring status) {
    if (!status) return;
    const char* chars = env->GetStringUTFChars(status, nullptr);
    if (!chars) return;
    setState(chars);
    env->ReleaseStringUTFChars(status, chars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeUpdateToken(
        JNIEnv* env, jclass, jstring accessToken) {
    if (!client || !accessToken) {
        setState("Discord client is not initialized");
        return;
    }
    const char* chars = env->GetStringUTFChars(accessToken, nullptr);
    if (!chars) {
        setState("Unable to read Discord token");
        return;
    }
    std::string token(chars);
    env->ReleaseStringUTFChars(accessToken, chars);
    setState("Connecting");
    client->UpdateToken(discordpp::AuthorizationTokenType::Bearer, std::move(token),
        [](auto result) {
            if (!result.Successful()) {
                setState(result.ToString());
                return;
            }
            client->Connect();
        });
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeDisconnect(JNIEnv*, jclass) {
    if (client) client->Disconnect();
    connected = false;
    setState("Not connected");
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeRevokeToken(
        JNIEnv* env, jclass, jlong applicationId, jstring token) {
    if (!client || !token) return;
    const char* chars = env->GetStringUTFChars(token, nullptr);
    if (!chars) return;
    std::string value(chars);
    env->ReleaseStringUTFChars(token, chars);
    client->RevokeToken(static_cast<uint64_t>(applicationId), value, [](auto result) {
        if (!result.Successful()) setState("Not connected (remote revoke pending)");
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativePumpAndSnapshot(JNIEnv* env, jclass) {
    discordpp::RunCallbacks();
    if (snapshotDirty) {
        lastSnapshot.clear();
        refreshSnapshot(&lastSnapshot);
    }
    return toJavaStrings(env, lastSnapshot);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeRequestUserMessages(
        JNIEnv*, jclass, jlong recipientId, jlong requestId, jint limit) {
    if (!client || recipientId <= 0 || requestId < 0) {
        queueMessageEvent("HISTORY_RESULT", "", {"1", "HISTORY_RESULT",
                std::to_string(recipientId), std::to_string(requestId), "0", "0", "0", "ClientNotReady"});
        return;
    }
    const int32_t boundedLimit = std::max(1, std::min(30, static_cast<int32_t>(limit)));
    client->GetUserMessagesWithLimit(static_cast<uint64_t>(recipientId), boundedLimit,
            [recipientId, requestId](auto result, auto messages) {
                if (result.Successful()) {
                    queueMessageEvent("HISTORY_BEGIN", "", {"1", "HISTORY_BEGIN",
                            std::to_string(recipientId), std::to_string(requestId)});
                    for (const auto& message : messages) {
                        queueMessage("HISTORY_MESSAGE", static_cast<uint64_t>(recipientId), message,
                                static_cast<uint64_t>(requestId));
                    }
                }
                queueRequestResult("HISTORY_RESULT", static_cast<uint64_t>(recipientId),
                        static_cast<uint64_t>(requestId), result);
            });
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeSendUserMessage(
        JNIEnv* env, jclass, jlong recipientId, jlong requestId, jstring content) {
    if (!client || recipientId <= 0 || requestId < 0 || !content) {
        queueMessageEvent("SEND_RESULT", "", {"1", "SEND_RESULT", std::to_string(recipientId),
                std::to_string(requestId), "0", "0", "0", "ClientNotReady"});
        return;
    }
    std::string value;
    int codePointCount = 0;
    if (!jstringToUtf8(env, content, &value, &codePointCount)) return;
    if (value.empty() || codePointCount > 2000) {
        queueMessageEvent("SEND_RESULT", "", {"1", "SEND_RESULT", std::to_string(recipientId),
                std::to_string(requestId), "0", "0", "0", "ValidationError"});
        return;
    }
    client->SendUserMessage(static_cast<uint64_t>(recipientId), value,
            [recipientId, requestId](auto result, uint64_t messageId) {
                queueRequestResult("SEND_RESULT", static_cast<uint64_t>(recipientId),
                        static_cast<uint64_t>(requestId), result);
            });
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeOpenMessageInDiscord(
        JNIEnv*, jclass, jlong messageId) {
    if (!client || messageId <= 0) {
        queueMessageEvent("OPEN_MESSAGE_RESULT", "", {"1", "OPEN_MESSAGE_RESULT",
                std::to_string(messageId), "0", "ClientNotReady"});
        return;
    }
    const uint64_t id = static_cast<uint64_t>(messageId);
    client->OpenMessageInDiscord(id,
            [id]() {
                queueMessageEvent("OPEN_MESSAGE_RESULT", "", {"1", "OPEN_MESSAGE_RESULT",
                        std::to_string(id), "0", "ProvisionalUserMergeRequired"});
            },
            [id](auto result) { queueOpenMessageResult(id, result); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeSetShowingChat(JNIEnv*, jclass, jboolean showing) {
    if (client) client->SetShowingChat(showing == JNI_TRUE);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_limelight_discord_DiscordSocialClient_nativeDrainMessageEvents(JNIEnv* env, jclass) {
    std::vector<std::string> values;
    values.reserve(messageEvents.size());
    while (!messageEvents.empty()) {
        values.push_back(std::move(messageEvents.front().record));
        messageEvents.pop_front();
    }
    return toJavaStrings(env, values);
}
