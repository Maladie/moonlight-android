#pragma once

#include <windows.h>

#include <cstdint>
#include <string>
#include <vector>

namespace moonwaker
{
    constexpr BYTE kProtocolVersion = 1;
    constexpr size_t kMaximumFields = 8;
    constexpr size_t kMaximumFieldBytes = 4096;
    constexpr size_t kMaximumMessageBytes = 32768;

    struct Field
    {
        BYTE key = 0;
        std::vector<BYTE> value;
    };

    struct Reply
    {
        bool success = false;
        std::vector<Field> fields;

        Reply() = default;
        Reply(const Reply&) = delete;
        Reply& operator=(const Reply&) = delete;
        Reply(Reply&& other) noexcept;
        Reply& operator=(Reply&& other) noexcept;
        ~Reply();

        const std::vector<BYTE>* Find(BYTE key) const;
        void Clear();
    };

    void SecureClear(std::vector<BYTE>* value);
    void SecureClear(std::vector<wchar_t>* value);
    bool ShouldNotifyPendingAttempt(const std::wstring& attemptId,
        std::wstring* lastAttemptId);
    std::vector<BYTE> Utf8(const std::wstring& value);
    bool Utf8ToWide(const std::vector<BYTE>& value, std::vector<wchar_t>* result);
    bool BuildRequest(BYTE operation, const std::vector<Field>& fields,
        std::vector<BYTE>* request);
    bool ParseReply(const std::vector<BYTE>& bytes, Reply* reply);
}
