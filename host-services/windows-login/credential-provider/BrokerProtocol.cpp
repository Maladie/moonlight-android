#include "BrokerProtocol.h"

#include <algorithm>
#include <iterator>
#include <utility>

namespace
{
    constexpr BYTE kRequestMagic[] = {'M', 'W', 'L', 'B'};
    constexpr BYTE kReplyMagic[] = {'M', 'W', 'L', 'R'};

    void AppendUInt32(std::vector<BYTE>* bytes, DWORD value)
    {
        bytes->push_back(static_cast<BYTE>(value));
        bytes->push_back(static_cast<BYTE>(value >> 8));
        bytes->push_back(static_cast<BYTE>(value >> 16));
        bytes->push_back(static_cast<BYTE>(value >> 24));
    }

    DWORD ReadUInt32(const std::vector<BYTE>& bytes, size_t offset)
    {
        return static_cast<DWORD>(bytes[offset]) |
            (static_cast<DWORD>(bytes[offset + 1]) << 8) |
            (static_cast<DWORD>(bytes[offset + 2]) << 16) |
            (static_cast<DWORD>(bytes[offset + 3]) << 24);
    }
}

namespace moonwaker
{
    Reply::Reply(Reply&& other) noexcept
        : success(other.success), fields(std::move(other.fields))
    {
        other.success = false;
    }

    Reply& Reply::operator=(Reply&& other) noexcept
    {
        if (this != &other)
        {
            Clear();
            success = other.success;
            fields = std::move(other.fields);
            other.success = false;
        }
        return *this;
    }

    Reply::~Reply()
    {
        Clear();
    }

    const std::vector<BYTE>* Reply::Find(BYTE key) const
    {
        for (const Field& field : fields)
        {
            if (field.key == key)
            {
                return &field.value;
            }
        }
        return nullptr;
    }

    void Reply::Clear()
    {
        for (Field& field : fields)
        {
            SecureClear(&field.value);
        }
        fields.clear();
        success = false;
    }

    void SecureClear(std::vector<BYTE>* value)
    {
        if (value == nullptr)
        {
            return;
        }
        if (!value->empty())
        {
            SecureZeroMemory(value->data(), value->size());
        }
        value->clear();
    }

    void SecureClear(std::vector<wchar_t>* value)
    {
        if (value == nullptr)
        {
            return;
        }
        if (!value->empty())
        {
            SecureZeroMemory(value->data(), value->size() * sizeof(wchar_t));
        }
        value->clear();
    }

    std::vector<BYTE> Utf8(const std::wstring& value)
    {
        if (value.empty())
        {
            return {};
        }
        const int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
            value.data(), static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
        if (size <= 0)
        {
            return {};
        }
        std::vector<BYTE> result(static_cast<size_t>(size));
        if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(),
                static_cast<int>(value.size()), reinterpret_cast<char*>(result.data()),
                size, nullptr, nullptr) != size)
        {
            SecureClear(&result);
        }
        return result;
    }

    bool Utf8ToWide(const std::vector<BYTE>& value, std::vector<wchar_t>* result)
    {
        if (result == nullptr || value.empty())
        {
            return false;
        }
        SecureClear(result);
        const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
            reinterpret_cast<const char*>(value.data()), static_cast<int>(value.size()),
            nullptr, 0);
        if (size <= 0)
        {
            return false;
        }
        result->resize(static_cast<size_t>(size) + 1);
        if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                reinterpret_cast<const char*>(value.data()), static_cast<int>(value.size()),
                result->data(), size) != size)
        {
            SecureClear(result);
            return false;
        }
        (*result)[static_cast<size_t>(size)] = L'\0';
        return true;
    }

    bool BuildRequest(BYTE operation, const std::vector<Field>& fields,
        std::vector<BYTE>* request)
    {
        if (request == nullptr || fields.size() > kMaximumFields)
        {
            return false;
        }
        SecureClear(request);
        size_t total = 8;
        for (size_t index = 0; index < fields.size(); ++index)
        {
            if (fields[index].value.size() > kMaximumFieldBytes ||
                total + 5 + fields[index].value.size() > kMaximumMessageBytes)
            {
                return false;
            }
            for (size_t previous = 0; previous < index; ++previous)
            {
                if (fields[index].key == fields[previous].key)
                {
                    return false;
                }
            }
            total += 5 + fields[index].value.size();
        }
        request->reserve(total);
        request->insert(request->end(), std::begin(kRequestMagic), std::end(kRequestMagic));
        request->push_back(kProtocolVersion);
        request->push_back(operation);
        request->push_back(static_cast<BYTE>(fields.size()));
        request->push_back(0);
        for (const Field& field : fields)
        {
            request->push_back(field.key);
            AppendUInt32(request, static_cast<DWORD>(field.value.size()));
            request->insert(request->end(), field.value.begin(), field.value.end());
        }
        return true;
    }

    bool ParseReply(const std::vector<BYTE>& bytes, Reply* reply)
    {
        if (reply == nullptr)
        {
            return false;
        }
        reply->Clear();
        if (bytes.size() < 8 || bytes.size() > kMaximumMessageBytes ||
            !std::equal(std::begin(kReplyMagic), std::end(kReplyMagic), bytes.begin()) ||
            bytes[4] != kProtocolVersion || bytes[5] > 1 ||
            bytes[6] > kMaximumFields || bytes[7] != 0)
        {
            return false;
        }
        size_t offset = 8;
        for (size_t index = 0; index < bytes[6]; ++index)
        {
            if (bytes.size() - offset < 5)
            {
                reply->Clear();
                return false;
            }
            Field field;
            field.key = bytes[offset];
            const DWORD length = ReadUInt32(bytes, offset + 1);
            offset += 5;
            if (length > kMaximumFieldBytes || length > bytes.size() - offset ||
                offset + length > kMaximumMessageBytes || reply->Find(field.key) != nullptr)
            {
                reply->Clear();
                return false;
            }
            field.value.assign(bytes.begin() + offset, bytes.begin() + offset + length);
            reply->fields.push_back(std::move(field));
            offset += length;
        }
        if (offset != bytes.size())
        {
            reply->Clear();
            return false;
        }
        reply->success = bytes[5] == 0;
        return true;
    }
}
