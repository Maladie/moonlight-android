#include <windows.h>
#include <credentialprovider.h>
#include <ntsecapi.h>
#include <sddl.h>
#include <wincred.h>

#include <algorithm>
#include <climits>
#include <cstdint>
#include <cstring>
#include <cwchar>
#include <new>
#include <string>
#include <utility>
#include <vector>

#include "BrokerProtocol.h"

namespace
{
    // {9F7D193F-8057-4D2D-88A0-5C1B8D03C441}
    const CLSID CLSID_MoonWakerCredentialProvider =
        {0x9f7d193f, 0x8057, 0x4d2d, {0x88, 0xa0, 0x5c, 0x1b, 0x8d, 0x03, 0xc4, 0x41}};

    constexpr wchar_t kBrokerPipe[] = L"\\\\.\\pipe\\MoonWakerLoginBroker.Provider.v1";
    constexpr wchar_t kStreamHotkeyPipePrefix[] =
        L"\\\\.\\pipe\\MoonWakerHostControl.StreamHotkey.";
    constexpr wchar_t kAttemptEvent[] = L"Global\\MoonWaker.LoginAttempt.v1";
    constexpr wchar_t kSettingsKey[] =
        L"SOFTWARE\\MoonWaker\\WindowsLogin\\CredentialProvider";
    constexpr DWORD kPipeWaitMilliseconds = 750;

    constexpr BYTE kObserve = 1;
    constexpr BYTE kAcquire = 2;
    constexpr BYTE kReport = 3;
    constexpr int kStreamHotkeyId = 0x4D57;
    constexpr UINT kStreamHotkeyModifiers = MOD_ALT | MOD_CONTROL | MOD_SHIFT | 0x4000;

    volatile LONG g_liveObjects = 0;
    volatile LONG g_serverLocks = 0;

    enum FieldId : DWORD
    {
        FieldTitle,
        FieldAccount,
        FieldSubmit,
        FieldCount
    };

    const CREDENTIAL_PROVIDER_FIELD_TYPE kFieldTypes[FieldCount] =
    {
        CPFT_LARGE_TEXT,
        CPFT_SMALL_TEXT,
        CPFT_SUBMIT_BUTTON
    };

    const wchar_t* const kFieldLabels[FieldCount] =
    {
        L"MoonWaker",
        L"Windows account",
        L"Sign in"
    };

    HRESULT DuplicateString(const wchar_t* source, wchar_t** destination)
    {
        if (destination == nullptr)
        {
            return E_INVALIDARG;
        }
        *destination = nullptr;
        if (source == nullptr)
        {
            source = L"";
        }
        const size_t characters = wcslen(source) + 1;
        if (characters > (SIZE_MAX / sizeof(wchar_t)))
        {
            return E_OUTOFMEMORY;
        }
        wchar_t* copy = static_cast<wchar_t*>(
            CoTaskMemAlloc(characters * sizeof(wchar_t)));
        if (copy == nullptr)
        {
            return E_OUTOFMEMORY;
        }
        memcpy(copy, source, characters * sizeof(wchar_t));
        *destination = copy;
        return S_OK;
    }

    HRESULT Win32Failure(DWORD error)
    {
        return HRESULT_FROM_WIN32(error == ERROR_SUCCESS ? ERROR_GEN_FAILURE : error);
    }

    bool IsProviderEnabled()
    {
        DWORD value = 0;
        DWORD bytes = sizeof(value);
        return RegGetValueW(HKEY_LOCAL_MACHINE, kSettingsKey, L"Enabled",
            RRF_RT_REG_DWORD, nullptr, &value, &bytes) == ERROR_SUCCESS && value == 1;
    }

    void SignalRefresh()
    {
        HANDLE event = OpenEventW(EVENT_MODIFY_STATE, FALSE, kAttemptEvent);
        if (event != nullptr)
        {
            SetEvent(event);
            CloseHandle(event);
        }
    }

    void SignalStreamClose()
    {
        DWORD sessionId = 0;
        if (!ProcessIdToSessionId(GetCurrentProcessId(), &sessionId))
        {
            return;
        }
        const std::wstring pipeName = kStreamHotkeyPipePrefix +
            std::to_wstring(sessionId);
        if (!WaitNamedPipeW(pipeName.c_str(), 100))
        {
            return;
        }
        HANDLE pipe = CreateFileW(pipeName.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
            SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION, nullptr);
        if (pipe == INVALID_HANDLE_VALUE)
        {
            return;
        }
        const BYTE command = 1;
        DWORD written = 0;
        WriteFile(pipe, &command, sizeof(command), &written, nullptr);
        CloseHandle(pipe);
    }

    bool ValidBrokerIdentity(const std::wstring& account, const std::wstring& sidText)
    {
        if (account.empty() || account.size() > 256)
        {
            return false;
        }
        PSID expected = nullptr;
        if (!ConvertStringSidToSidW(sidText.c_str(), &expected))
        {
            return false;
        }
        const bool valid = IsValidSid(expected) != FALSE;
        LocalFree(expected);
        return valid;
    }

    bool UserArrayContainsSid(ICredentialProviderUserArray* users,
        const std::wstring& expectedSid)
    {
        if (users == nullptr)
        {
            return false;
        }
        DWORD count = 0;
        if (FAILED(users->GetCount(&count)))
        {
            return false;
        }
        for (DWORD index = 0; index < count; ++index)
        {
            ICredentialProviderUser* user = nullptr;
            if (FAILED(users->GetAt(index, &user)) || user == nullptr)
            {
                continue;
            }
            PWSTR sid = nullptr;
            const bool matches = SUCCEEDED(user->GetSid(&sid)) && sid != nullptr &&
                _wcsicmp(sid, expectedSid.c_str()) == 0;
            CoTaskMemFree(sid);
            user->Release();
            if (matches)
            {
                return true;
            }
        }
        return false;
    }

    bool IsSystemPipeServer(HANDLE pipe)
    {
        ULONG processId = 0;
        if (!GetNamedPipeServerProcessId(pipe, &processId))
        {
            return false;
        }
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, processId);
        if (process == nullptr)
        {
            return false;
        }
        HANDLE token = nullptr;
        if (!OpenProcessToken(process, TOKEN_QUERY, &token))
        {
            CloseHandle(process);
            return false;
        }
        DWORD required = 0;
        GetTokenInformation(token, TokenUser, nullptr, 0, &required);
        std::vector<BYTE> tokenUser(required);
        bool system = false;
        if (required != 0 && GetTokenInformation(token, TokenUser,
                tokenUser.data(), required, &required))
        {
            BYTE systemSid[SECURITY_MAX_SID_SIZE] = {};
            DWORD systemSidBytes = sizeof(systemSid);
            if (CreateWellKnownSid(WinLocalSystemSid, nullptr, systemSid,
                    &systemSidBytes))
            {
                const TOKEN_USER* user = reinterpret_cast<const TOKEN_USER*>(tokenUser.data());
                system = EqualSid(user->User.Sid, systemSid) != FALSE;
            }
        }
        if (!tokenUser.empty())
        {
            SecureZeroMemory(tokenUser.data(), tokenUser.size());
        }
        CloseHandle(token);
        CloseHandle(process);
        return system;
    }

    bool WriteAll(HANDLE pipe, const std::vector<BYTE>& bytes)
    {
        size_t offset = 0;
        while (offset < bytes.size())
        {
            DWORD written = 0;
            const DWORD chunk = static_cast<DWORD>(
                (std::min)(bytes.size() - offset, static_cast<size_t>(MAXDWORD)));
            if (!WriteFile(pipe, bytes.data() + offset, chunk, &written, nullptr) ||
                written == 0)
            {
                return false;
            }
            offset += written;
        }
        return true;
    }

    bool ReadAll(HANDLE pipe, BYTE* destination, DWORD bytes)
    {
        DWORD offset = 0;
        while (offset < bytes)
        {
            DWORD read = 0;
            if (!ReadFile(pipe, destination + offset, bytes - offset, &read, nullptr) ||
                read == 0)
            {
                return false;
            }
            offset += read;
        }
        return true;
    }

    DWORD ReadUInt32(const BYTE* bytes)
    {
        return static_cast<DWORD>(bytes[0]) |
            (static_cast<DWORD>(bytes[1]) << 8) |
            (static_cast<DWORD>(bytes[2]) << 16) |
            (static_cast<DWORD>(bytes[3]) << 24);
    }

    bool ReadBrokerReply(HANDLE pipe, std::vector<BYTE>* bytes)
    {
        if (bytes == nullptr)
        {
            return false;
        }
        moonwaker::SecureClear(bytes);
        BYTE header[8] = {};
        if (!ReadAll(pipe, header, sizeof(header)) ||
            memcmp(header, "MWLR", 4) != 0 ||
            header[4] != moonwaker::kProtocolVersion || header[5] > 1 ||
            header[6] > moonwaker::kMaximumFields || header[7] != 0)
        {
            SecureZeroMemory(header, sizeof(header));
            return false;
        }
        bytes->assign(header, header + sizeof(header));
        const BYTE fieldCount = header[6];
        SecureZeroMemory(header, sizeof(header));
        for (BYTE index = 0; index < fieldCount; ++index)
        {
            BYTE fieldHeader[5] = {};
            if (!ReadAll(pipe, fieldHeader, sizeof(fieldHeader)))
            {
                moonwaker::SecureClear(bytes);
                return false;
            }
            const DWORD length = ReadUInt32(fieldHeader + 1);
            if (length > moonwaker::kMaximumFieldBytes ||
                bytes->size() + sizeof(fieldHeader) + length >
                    moonwaker::kMaximumMessageBytes)
            {
                SecureZeroMemory(fieldHeader, sizeof(fieldHeader));
                moonwaker::SecureClear(bytes);
                return false;
            }
            bytes->insert(bytes->end(), fieldHeader, fieldHeader + sizeof(fieldHeader));
            SecureZeroMemory(fieldHeader, sizeof(fieldHeader));
            const size_t offset = bytes->size();
            bytes->resize(offset + length);
            if (length != 0 && !ReadAll(pipe, bytes->data() + offset, length))
            {
                moonwaker::SecureClear(bytes);
                return false;
            }
        }
        return true;
    }

    bool BrokerCall(BYTE operation, const std::vector<moonwaker::Field>& fields,
        moonwaker::Reply* reply)
    {
        std::vector<BYTE> request;
        if (reply == nullptr || !moonwaker::BuildRequest(operation, fields, &request))
        {
            return false;
        }
        if (!WaitNamedPipeW(kBrokerPipe, kPipeWaitMilliseconds))
        {
            moonwaker::SecureClear(&request);
            return false;
        }
        HANDLE pipe = CreateFileW(kBrokerPipe, GENERIC_READ | GENERIC_WRITE, 0,
            nullptr, OPEN_EXISTING,
            SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION, nullptr);
        if (pipe == INVALID_HANDLE_VALUE)
        {
            moonwaker::SecureClear(&request);
            return false;
        }
        bool ok = IsSystemPipeServer(pipe) && WriteAll(pipe, request);
        moonwaker::SecureClear(&request);
        std::vector<BYTE> response;
        if (ok)
        {
            ok = ReadBrokerReply(pipe, &response) &&
                moonwaker::ParseReply(response, reply);
        }
        moonwaker::SecureClear(&response);
        CloseHandle(pipe);
        return ok;
    }

    bool ReadText(const moonwaker::Reply& reply, BYTE key, size_t maximumCharacters,
        std::wstring* text)
    {
        if (text == nullptr)
        {
            return false;
        }
        const std::vector<BYTE>* encoded = reply.Find(key);
        std::vector<wchar_t> characters;
        if (encoded == nullptr || !moonwaker::Utf8ToWide(*encoded, &characters) ||
            characters.size() <= 1 || characters.size() - 1 > maximumCharacters ||
            std::find(characters.begin(), characters.end() - 1, L'\0') !=
                characters.end() - 1)
        {
            moonwaker::SecureClear(&characters);
            return false;
        }
        text->assign(characters.data(), characters.size() - 1);
        moonwaker::SecureClear(&characters);
        return true;
    }

    moonwaker::Field TextField(BYTE key, const std::wstring& value)
    {
        moonwaker::Field field;
        field.key = key;
        field.value = moonwaker::Utf8(value);
        return field;
    }

    struct AttemptDescriptor
    {
        std::wstring attemptId;
        std::wstring clientId;
        std::wstring profileId;
        std::wstring requestId;
        std::wstring sid;
        std::wstring account;

        bool SameAttempt(const AttemptDescriptor& other) const
        {
            return attemptId == other.attemptId && clientId == other.clientId &&
                profileId == other.profileId && requestId == other.requestId &&
                sid == other.sid && account == other.account;
        }
    };

    class BrokerClient
    {
    public:
        bool Observe(AttemptDescriptor* attempt) const
        {
            if (attempt == nullptr)
            {
                return false;
            }
            moonwaker::Reply reply;
            const std::vector<moonwaker::Field> fields;
            if (!BrokerCall(kObserve, fields, &reply) || !reply.success)
            {
                return false;
            }
            std::wstring state;
            if (!ReadText(reply, 1, 32, &state) || state != L"pending")
            {
                return false;
            }
            return ReadText(reply, 3, 64, &attempt->attemptId) &&
                ReadText(reply, 4, 128, &attempt->clientId) &&
                ReadText(reply, 5, 64, &attempt->profileId) &&
                ReadText(reply, 6, 128, &attempt->requestId) &&
                ReadText(reply, 7, 184, &attempt->sid) &&
                ReadText(reply, 8, 256, &attempt->account);
        }

        bool Acquire(const AttemptDescriptor& attempt, std::wstring* account,
            std::wstring* sid, std::vector<wchar_t>* password) const
        {
            if (account == nullptr || sid == nullptr || password == nullptr)
            {
                return false;
            }
            std::vector<moonwaker::Field> fields;
            fields.push_back(TextField(1, attempt.attemptId));
            fields.push_back(TextField(2, attempt.clientId));
            fields.push_back(TextField(3, attempt.profileId));
            fields.push_back(TextField(4, attempt.requestId));
            moonwaker::Reply reply;
            if (!BrokerCall(kAcquire, fields, &reply) || !reply.success ||
                !ReadText(reply, 3, 256, account) ||
                !ReadText(reply, 5, 184, sid))
            {
                return false;
            }
            const std::vector<BYTE>* encoded = reply.Find(4);
            if (encoded == nullptr || !moonwaker::Utf8ToWide(*encoded, password) ||
                password->size() <= 1 || password->size() > 257 ||
                std::find(password->begin(), password->end() - 1, L'\0') !=
                    password->end() - 1)
            {
                moonwaker::SecureClear(password);
                return false;
            }
            return true;
        }

        void Report(const AttemptDescriptor& attempt, bool success,
            const wchar_t* reason) const
        {
            std::vector<moonwaker::Field> fields;
            fields.push_back(TextField(1, attempt.attemptId));
            fields.push_back(TextField(2, attempt.clientId));
            fields.push_back(TextField(3, attempt.profileId));
            fields.push_back(TextField(4, attempt.requestId));
            fields.push_back(TextField(5, success ? L"success" : L"failure"));
            fields.push_back(TextField(6, reason == nullptr ? L"" : reason));
            moonwaker::Reply ignored;
            BrokerCall(kReport, fields, &ignored);
        }
    };

    HRESULT RetrieveNegotiatePackage(ULONG* package)
    {
        if (package == nullptr)
        {
            return E_INVALIDARG;
        }
        HANDLE lsa = nullptr;
        NTSTATUS status = LsaConnectUntrusted(&lsa);
        if (status < 0)
        {
            return HRESULT_FROM_WIN32(LsaNtStatusToWinError(status));
        }
        char name[] = "Negotiate";
        LSA_STRING packageName = {};
        packageName.Buffer = name;
        packageName.Length = static_cast<USHORT>(strlen(name));
        packageName.MaximumLength = packageName.Length + 1;
        status = LsaLookupAuthenticationPackage(lsa, &packageName, package);
        LsaDeregisterLogonProcess(lsa);
        return status < 0
            ? HRESULT_FROM_WIN32(LsaNtStatusToWinError(status))
            : S_OK;
    }

    HRESULT ProtectPassword(std::vector<wchar_t>* password,
        std::vector<wchar_t>* protectedPassword)
    {
        if (password == nullptr || protectedPassword == nullptr || password->size() <= 1)
        {
            return E_INVALIDARG;
        }
        DWORD protectedCharacters = 0;
        SetLastError(ERROR_SUCCESS);
        CredProtectW(FALSE, password->data(), static_cast<DWORD>(password->size()),
            nullptr, &protectedCharacters, nullptr);
        const DWORD sizingError = GetLastError();
        if (sizingError != ERROR_INSUFFICIENT_BUFFER || protectedCharacters == 0 ||
            protectedCharacters > moonwaker::kMaximumFieldBytes / sizeof(wchar_t))
        {
            return Win32Failure(sizingError == ERROR_SUCCESS
                ? ERROR_INVALID_DATA : sizingError);
        }
        protectedPassword->resize(protectedCharacters);
        if (!CredProtectW(FALSE, password->data(), static_cast<DWORD>(password->size()),
                protectedPassword->data(), &protectedCharacters, nullptr) ||
            protectedCharacters == 0 ||
            protectedCharacters > protectedPassword->size() ||
            (*protectedPassword)[protectedCharacters - 1] != L'\0')
        {
            const HRESULT result = Win32Failure(GetLastError());
            moonwaker::SecureClear(protectedPassword);
            return result;
        }
        protectedPassword->resize(protectedCharacters);
        return S_OK;
    }

    bool SplitLocalAccount(const std::wstring& account, std::wstring* domain,
        std::wstring* user)
    {
        if (domain == nullptr || user == nullptr)
        {
            return false;
        }
        const size_t separator = account.find(L'\\');
        if (separator == std::wstring::npos || separator == 0 ||
            separator + 1 >= account.size() ||
            account.find(L'\\', separator + 1) != std::wstring::npos)
        {
            return false;
        }
        *domain = account.substr(0, separator);
        *user = account.substr(separator + 1);
        return true;
    }

    HRESULT UnicodeStringInit(const std::wstring& value, UNICODE_STRING* output)
    {
        if (output == nullptr || value.size() > USHRT_MAX / sizeof(wchar_t))
        {
            return E_INVALIDARG;
        }
        output->Length = static_cast<USHORT>(value.size() * sizeof(wchar_t));
        output->MaximumLength = output->Length;
        output->Buffer = const_cast<PWSTR>(value.data());
        return S_OK;
    }

    HRESULT KerbInteractiveUnlockLogonInit(const std::wstring& domain,
        const std::wstring& user, std::vector<wchar_t>* protectedPassword,
        CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
        KERB_INTERACTIVE_UNLOCK_LOGON* unlockLogon)
    {
        if (protectedPassword == nullptr || protectedPassword->empty() ||
            unlockLogon == nullptr)
        {
            return E_INVALIDARG;
        }
        ZeroMemory(unlockLogon, sizeof(*unlockLogon));
        HRESULT result = UnicodeStringInit(domain,
            &unlockLogon->Logon.LogonDomainName);
        if (SUCCEEDED(result))
        {
            result = UnicodeStringInit(user, &unlockLogon->Logon.UserName);
        }
        if (SUCCEEDED(result))
        {
            const size_t protectedCharacters = protectedPassword->size() - 1;
            if (protectedCharacters > USHRT_MAX / sizeof(wchar_t))
            {
                result = E_INVALIDARG;
            }
            else
            {
                unlockLogon->Logon.Password.Length = static_cast<USHORT>(
                    protectedCharacters * sizeof(wchar_t));
                unlockLogon->Logon.Password.MaximumLength =
                    unlockLogon->Logon.Password.Length;
                unlockLogon->Logon.Password.Buffer = protectedPassword->data();
            }
        }
        if (FAILED(result))
        {
            return result;
        }
        if (scenario == CPUS_LOGON)
        {
            unlockLogon->Logon.MessageType = KerbInteractiveLogon;
        }
        else if (scenario == CPUS_UNLOCK_WORKSTATION)
        {
            unlockLogon->Logon.MessageType = KerbWorkstationUnlockLogon;
        }
        else
        {
            return E_NOTIMPL;
        }
        return S_OK;
    }

    void PackUnicodeString(const UNICODE_STRING& source, BYTE** cursor,
        BYTE* base, UNICODE_STRING* destination)
    {
        destination->Length = source.Length;
        destination->MaximumLength = source.Length;
        if (source.Length == 0)
        {
            destination->Buffer = nullptr;
            return;
        }
        CopyMemory(*cursor, source.Buffer, source.Length);
        destination->Buffer = reinterpret_cast<PWSTR>(*cursor - base);
        *cursor += source.Length;
    }

    HRESULT KerbInteractiveUnlockLogonPack(
        const KERB_INTERACTIVE_UNLOCK_LOGON& input, BYTE** bytes, DWORD* byteCount)
    {
        if (bytes == nullptr || byteCount == nullptr)
        {
            return E_INVALIDARG;
        }
        *bytes = nullptr;
        *byteCount = 0;
        const size_t total = sizeof(input) + input.Logon.LogonDomainName.Length +
            input.Logon.UserName.Length + input.Logon.Password.Length;
        if (total > MAXDWORD)
        {
            return HRESULT_FROM_WIN32(ERROR_ARITHMETIC_OVERFLOW);
        }
        KERB_INTERACTIVE_UNLOCK_LOGON* packed =
            static_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(CoTaskMemAlloc(total));
        if (packed == nullptr)
        {
            return E_OUTOFMEMORY;
        }
        ZeroMemory(packed, sizeof(*packed));
        packed->Logon.MessageType = input.Logon.MessageType;
        BYTE* cursor = reinterpret_cast<BYTE*>(packed) + sizeof(*packed);
        BYTE* base = reinterpret_cast<BYTE*>(packed);
        PackUnicodeString(input.Logon.LogonDomainName, &cursor, base,
            &packed->Logon.LogonDomainName);
        PackUnicodeString(input.Logon.UserName, &cursor, base,
            &packed->Logon.UserName);
        PackUnicodeString(input.Logon.Password, &cursor, base,
            &packed->Logon.Password);
        *bytes = reinterpret_cast<BYTE*>(packed);
        *byteCount = static_cast<DWORD>(total);
        return S_OK;
    }

    class Credential final : public ICredentialProviderCredential2
    {
    public:
        Credential(const AttemptDescriptor& attempt,
            CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario)
            : referenceCount_(1), attempt_(attempt), scenario_(scenario)
        {
            InterlockedIncrement(&g_liveObjects);
        }

        IFACEMETHODIMP QueryInterface(REFIID iid, void** object) override
        {
            if (object == nullptr)
            {
                return E_INVALIDARG;
            }
            *object = nullptr;
            if (iid == IID_IUnknown || iid == IID_ICredentialProviderCredential ||
                iid == IID_ICredentialProviderCredential2)
            {
                *object = static_cast<ICredentialProviderCredential2*>(this);
                AddRef();
                return S_OK;
            }
            return E_NOINTERFACE;
        }

        IFACEMETHODIMP_(ULONG) AddRef() override
        {
            return static_cast<ULONG>(InterlockedIncrement(&referenceCount_));
        }

        IFACEMETHODIMP_(ULONG) Release() override
        {
            const LONG remaining = InterlockedDecrement(&referenceCount_);
            if (remaining == 0)
            {
                delete this;
            }
            return static_cast<ULONG>(remaining);
        }

        IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents* events) override
        {
            if (credentialEvents_ != nullptr)
            {
                credentialEvents_->Release();
            }
            credentialEvents_ = events;
            if (credentialEvents_ != nullptr)
            {
                credentialEvents_->AddRef();
            }
            return S_OK;
        }

        IFACEMETHODIMP UnAdvise() override
        {
            if (credentialEvents_ != nullptr)
            {
                credentialEvents_->Release();
                credentialEvents_ = nullptr;
            }
            return S_OK;
        }

        IFACEMETHODIMP SetSelected(BOOL* autoLogon) override
        {
            if (autoLogon == nullptr)
            {
                return E_INVALIDARG;
            }
            *autoLogon = FALSE;
            return S_OK;
        }

        IFACEMETHODIMP SetDeselected() override
        {
            return S_OK;
        }

        IFACEMETHODIMP GetFieldState(DWORD fieldId,
            CREDENTIAL_PROVIDER_FIELD_STATE* state,
            CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* interactiveState) override
        {
            if (fieldId >= FieldCount || state == nullptr || interactiveState == nullptr)
            {
                return E_INVALIDARG;
            }
            *state = fieldId == FieldTitle
                ? CPFS_DISPLAY_IN_BOTH
                : CPFS_DISPLAY_IN_SELECTED_TILE;
            *interactiveState = CPFIS_NONE;
            return S_OK;
        }

        IFACEMETHODIMP GetStringValue(DWORD fieldId, PWSTR* value) override
        {
            if (fieldId >= FieldCount || value == nullptr)
            {
                return E_INVALIDARG;
            }
            if (fieldId == FieldAccount)
            {
                return DuplicateString(attempt_.account.c_str(), value);
            }
            return DuplicateString(kFieldLabels[fieldId], value);
        }

        IFACEMETHODIMP GetBitmapValue(DWORD, HBITMAP*) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP GetCheckboxValue(DWORD, BOOL*, PWSTR*) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP GetSubmitButtonValue(DWORD fieldId, DWORD* adjacentTo) override
        {
            if (fieldId != FieldSubmit || adjacentTo == nullptr)
            {
                return E_INVALIDARG;
            }
            *adjacentTo = FieldAccount;
            return S_OK;
        }

        IFACEMETHODIMP GetComboBoxValueCount(DWORD, DWORD*, DWORD*) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP GetComboBoxValueAt(DWORD, DWORD, PWSTR*) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP SetStringValue(DWORD, PCWSTR) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP SetCheckboxValue(DWORD, BOOL) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP SetComboBoxSelectedValue(DWORD, DWORD) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP CommandLinkClicked(DWORD) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP GetSerialization(
            CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
            CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* serialization,
            PWSTR* optionalStatusText,
            CREDENTIAL_PROVIDER_STATUS_ICON* optionalStatusIcon) override
        {
            if (response == nullptr || serialization == nullptr)
            {
                return E_INVALIDARG;
            }
            *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
            ZeroMemory(serialization, sizeof(*serialization));
            if (optionalStatusText != nullptr)
            {
                *optionalStatusText = nullptr;
            }
            if (optionalStatusIcon != nullptr)
            {
                *optionalStatusIcon = CPSI_NONE;
            }

            if (!IsProviderEnabled() ||
                InterlockedCompareExchange(&serializationStarted_, 1, 0) != 0)
            {
                SignalRefresh();
                return S_OK;
            }

            BrokerClient broker;
            std::wstring account;
            std::wstring sid;
            std::vector<wchar_t> password;
            if (!broker.Acquire(attempt_, &account, &sid, &password))
            {
                broker.Report(attempt_, false, L"acquire_failed");
                InterlockedExchange(&reported_, 1);
                SignalRefresh();
                if (optionalStatusText != nullptr)
                {
                    DuplicateString(L"MoonWaker sign-in request is no longer available.",
                        optionalStatusText);
                }
                if (optionalStatusIcon != nullptr)
                {
                    *optionalStatusIcon = CPSI_WARNING;
                }
                return S_OK;
            }
            acquired_ = true;

            HRESULT result = E_ACCESSDENIED;
            if (account == attempt_.account && sid == attempt_.sid &&
                ValidBrokerIdentity(account, sid))
            {
                std::wstring domain;
                std::wstring user;
                std::vector<wchar_t> protectedPassword;
                if (SplitLocalAccount(account, &domain, &user))
                {
                    result = ProtectPassword(&password, &protectedPassword);
                    if (SUCCEEDED(result))
                    {
                        KERB_INTERACTIVE_UNLOCK_LOGON unlockLogon = {};
                        result = KerbInteractiveUnlockLogonInit(domain, user,
                            &protectedPassword, scenario_, &unlockLogon);
                        if (SUCCEEDED(result))
                        {
                            result = KerbInteractiveUnlockLogonPack(unlockLogon,
                                &serialization->rgbSerialization,
                                &serialization->cbSerialization);
                            if (SUCCEEDED(result))
                            {
                                result = RetrieveNegotiatePackage(
                                    &serialization->ulAuthenticationPackage);
                                if (SUCCEEDED(result))
                                {
                                    serialization->clsidCredentialProvider =
                                        CLSID_MoonWakerCredentialProvider;
                                    *response = CPGSR_RETURN_CREDENTIAL_FINISHED;
                                }
                                else
                                {
                                    SecureZeroMemory(serialization->rgbSerialization,
                                        serialization->cbSerialization);
                                    CoTaskMemFree(serialization->rgbSerialization);
                                    ZeroMemory(serialization, sizeof(*serialization));
                                }
                            }
                        }
                        SecureZeroMemory(&unlockLogon, sizeof(unlockLogon));
                    }
                }
                moonwaker::SecureClear(&protectedPassword);
            }
            moonwaker::SecureClear(&password);

            if (FAILED(result))
            {
                broker.Report(attempt_, false, L"serialization_failed");
                InterlockedExchange(&reported_, 1);
                SignalRefresh();
                if (optionalStatusText != nullptr)
                {
                    DuplicateString(L"MoonWaker could not prepare Windows sign-in.",
                        optionalStatusText);
                }
                if (optionalStatusIcon != nullptr)
                {
                    *optionalStatusIcon = CPSI_ERROR;
                }
                return S_OK;
            }
            return S_OK;
        }

        IFACEMETHODIMP ReportResult(NTSTATUS status, NTSTATUS,
            PWSTR* optionalStatusText,
            CREDENTIAL_PROVIDER_STATUS_ICON* optionalStatusIcon) override
        {
            if (optionalStatusText != nullptr)
            {
                *optionalStatusText = nullptr;
            }
            if (optionalStatusIcon != nullptr)
            {
                *optionalStatusIcon = CPSI_NONE;
            }
            if (acquired_ && InterlockedCompareExchange(&reported_, 1, 0) == 0)
            {
                const bool success = status == 0;
                BrokerClient().Report(attempt_, success,
                    success ? L"none" : L"windows_logon_failed");
                if (!success && optionalStatusText != nullptr)
                {
                    DuplicateString(
                        L"MoonWaker sign-in failed. Use a normal Windows sign-in option.",
                        optionalStatusText);
                }
                if (!success && optionalStatusIcon != nullptr)
                {
                    *optionalStatusIcon = CPSI_ERROR;
                }
                SignalRefresh();
            }
            return S_OK;
        }

        IFACEMETHODIMP GetUserSid(PWSTR* sid) override
        {
            return DuplicateString(attempt_.sid.c_str(), sid);
        }

    private:
        ~Credential()
        {
            UnAdvise();
            InterlockedDecrement(&g_liveObjects);
        }

        volatile LONG referenceCount_;
        AttemptDescriptor attempt_;
        CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario_;
        ICredentialProviderCredentialEvents* credentialEvents_ = nullptr;
        volatile LONG serializationStarted_ = 0;
        volatile LONG reported_ = 0;
        bool acquired_ = false;
    };

    class Provider final : public ICredentialProvider,
        public ICredentialProviderSetUserArray
    {
    public:
        Provider() : referenceCount_(1)
        {
            InitializeCriticalSection(&eventLock_);
            stopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
            hotkeyStopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
            attemptEvent_ = CreateEventW(nullptr, FALSE, FALSE, kAttemptEvent);
            InterlockedIncrement(&g_liveObjects);
        }

        IFACEMETHODIMP QueryInterface(REFIID iid, void** object) override
        {
            if (object == nullptr)
            {
                return E_INVALIDARG;
            }
            *object = nullptr;
            if (iid == IID_IUnknown || iid == IID_ICredentialProvider)
            {
                *object = static_cast<ICredentialProvider*>(this);
                AddRef();
                return S_OK;
            }
            if (iid == IID_ICredentialProviderSetUserArray)
            {
                *object = static_cast<ICredentialProviderSetUserArray*>(this);
                AddRef();
                return S_OK;
            }
            return E_NOINTERFACE;
        }

        IFACEMETHODIMP_(ULONG) AddRef() override
        {
            return static_cast<ULONG>(InterlockedIncrement(&referenceCount_));
        }

        IFACEMETHODIMP_(ULONG) Release() override
        {
            const LONG remaining = InterlockedDecrement(&referenceCount_);
            if (remaining == 0)
            {
                delete this;
            }
            return static_cast<ULONG>(remaining);
        }

        IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario,
            DWORD) override
        {
            if (scenario != CPUS_LOGON && scenario != CPUS_UNLOCK_WORKSTATION)
            {
                scenario_ = CPUS_INVALID;
                ReleaseCredential();
                return scenario == CPUS_CREDUI || scenario == CPUS_CHANGE_PASSWORD ||
                    scenario == CPUS_PLAP
                    ? E_NOTIMPL
                    : E_INVALIDARG;
            }
            if (scenario_ != scenario)
            {
                ReleaseCredential();
            }
            scenario_ = scenario;
            return S_OK;
        }

        IFACEMETHODIMP SetSerialization(
            const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) override
        {
            return E_NOTIMPL;
        }

        IFACEMETHODIMP Advise(ICredentialProviderEvents* events,
            UINT_PTR context) override
        {
            UnAdvise();
            if (events == nullptr)
            {
                return E_INVALIDARG;
            }
            EnterCriticalSection(&eventLock_);
            events_ = events;
            events_->AddRef();
            adviseContext_ = context;
            ResetEvent(stopEvent_);
            LeaveCriticalSection(&eventLock_);
            if (stopEvent_ != nullptr && attemptEvent_ != nullptr)
            {
                AddRef();
                notifierThread_ = CreateThread(nullptr, 0, NotifierMain, this, 0,
                    &notifierThreadId_);
                if (notifierThread_ == nullptr)
                {
                    const DWORD error = GetLastError();
                    Release();
                    UnAdvise();
                    return Win32Failure(error);
                }
            }
            if (hotkeyStopEvent_ != nullptr && IsProviderEnabled())
            {
                ResetEvent(hotkeyStopEvent_);
                AddRef();
                hotkeyThread_ = CreateThread(nullptr, 0, HotkeyMain, this, 0,
                    &hotkeyThreadId_);
                if (hotkeyThread_ == nullptr)
                {
                    hotkeyThreadId_ = 0;
                    Release();
                }
            }
            return S_OK;
        }

        IFACEMETHODIMP UnAdvise() override
        {
            if (stopEvent_ != nullptr)
            {
                SetEvent(stopEvent_);
            }
            if (hotkeyStopEvent_ != nullptr)
            {
                SetEvent(hotkeyStopEvent_);
            }
            HANDLE thread = notifierThread_;
            if (thread != nullptr)
            {
                if (GetCurrentThreadId() != notifierThreadId_)
                {
                    WaitForSingleObject(thread, 2000);
                }
                CloseHandle(thread);
                notifierThread_ = nullptr;
                notifierThreadId_ = 0;
            }
            thread = hotkeyThread_;
            if (thread != nullptr)
            {
                if (GetCurrentThreadId() != hotkeyThreadId_)
                {
                    WaitForSingleObject(thread, 2000);
                }
                CloseHandle(thread);
                hotkeyThread_ = nullptr;
                hotkeyThreadId_ = 0;
            }
            EnterCriticalSection(&eventLock_);
            ICredentialProviderEvents* events = events_;
            events_ = nullptr;
            adviseContext_ = 0;
            LeaveCriticalSection(&eventLock_);
            if (events != nullptr)
            {
                events->Release();
            }
            return S_OK;
        }

        IFACEMETHODIMP GetFieldDescriptorCount(DWORD* count) override
        {
            if (count == nullptr)
            {
                return E_INVALIDARG;
            }
            *count = FieldCount;
            return S_OK;
        }

        IFACEMETHODIMP GetFieldDescriptorAt(DWORD index,
            CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** descriptor) override
        {
            if (index >= FieldCount || descriptor == nullptr)
            {
                return E_INVALIDARG;
            }
            *descriptor = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
                CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
            if (*descriptor == nullptr)
            {
                return E_OUTOFMEMORY;
            }
            ZeroMemory(*descriptor, sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR));
            (*descriptor)->dwFieldID = index;
            (*descriptor)->cpft = kFieldTypes[index];
            const HRESULT result = DuplicateString(kFieldLabels[index],
                &(*descriptor)->pszLabel);
            if (FAILED(result))
            {
                CoTaskMemFree(*descriptor);
                *descriptor = nullptr;
            }
            return result;
        }

        IFACEMETHODIMP GetCredentialCount(DWORD* count, DWORD* defaultIndex,
            BOOL* autoLogonWithDefault) override
        {
            if (count == nullptr || defaultIndex == nullptr ||
                autoLogonWithDefault == nullptr)
            {
                return E_INVALIDARG;
            }
            RefreshCredential();
            if (credential_ == nullptr)
            {
                *count = 0;
                *defaultIndex = CREDENTIAL_PROVIDER_NO_DEFAULT;
                *autoLogonWithDefault = FALSE;
            }
            else
            {
                *count = 1;
                *defaultIndex = 0;
                *autoLogonWithDefault = TRUE;
            }
            return S_OK;
        }

        IFACEMETHODIMP GetCredentialAt(DWORD index,
            ICredentialProviderCredential** credential) override
        {
            if (credential == nullptr)
            {
                return E_INVALIDARG;
            }
            *credential = nullptr;
            RefreshCredential();
            if (index != 0 || credential_ == nullptr)
            {
                return E_INVALIDARG;
            }
            return credential_->QueryInterface(IID_ICredentialProviderCredential,
                reinterpret_cast<void**>(credential));
        }

        IFACEMETHODIMP SetUserArray(ICredentialProviderUserArray* users) override
        {
            if (users == nullptr)
            {
                return E_INVALIDARG;
            }
            users->AddRef();
            if (userArray_ != nullptr)
            {
                userArray_->Release();
            }
            userArray_ = users;
            ReleaseCredential();
            return S_OK;
        }

    private:
        ~Provider()
        {
            UnAdvise();
            ReleaseCredential();
            if (userArray_ != nullptr)
            {
                userArray_->Release();
            }
            if (attemptEvent_ != nullptr)
            {
                CloseHandle(attemptEvent_);
            }
            if (stopEvent_ != nullptr)
            {
                CloseHandle(stopEvent_);
            }
            if (hotkeyStopEvent_ != nullptr)
            {
                CloseHandle(hotkeyStopEvent_);
            }
            DeleteCriticalSection(&eventLock_);
            InterlockedDecrement(&g_liveObjects);
        }

        static DWORD WINAPI NotifierMain(void* context)
        {
            Provider* self = static_cast<Provider*>(context);
            const HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
            HANDLE waits[] = {self->stopEvent_, self->attemptEvent_};
            while (WaitForMultipleObjects(2, waits, FALSE, INFINITE) ==
                WAIT_OBJECT_0 + 1)
            {
                ICredentialProviderEvents* events = nullptr;
                UINT_PTR adviseContext = 0;
                EnterCriticalSection(&self->eventLock_);
                if (self->events_ != nullptr)
                {
                    events = self->events_;
                    events->AddRef();
                    adviseContext = self->adviseContext_;
                }
                LeaveCriticalSection(&self->eventLock_);
                if (events != nullptr)
                {
                    events->CredentialsChanged(adviseContext);
                    events->Release();
                }
            }
            if (SUCCEEDED(initialized))
            {
                CoUninitialize();
            }
            self->Release();
            return 0;
        }

        static DWORD WINAPI HotkeyMain(void* context)
        {
            Provider* self = static_cast<Provider*>(context);
            const bool registered = RegisterHotKey(nullptr, kStreamHotkeyId,
                kStreamHotkeyModifiers, VK_END) != FALSE;
            if (registered)
            {
                bool running = true;
                while (running)
                {
                    const DWORD wait = MsgWaitForMultipleObjects(1,
                        &self->hotkeyStopEvent_, FALSE, INFINITE, QS_ALLINPUT);
                    if (wait == WAIT_OBJECT_0)
                    {
                        break;
                    }
                    if (wait != WAIT_OBJECT_0 + 1)
                    {
                        break;
                    }
                    MSG message = {};
                    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE))
                    {
                        if (message.message == WM_HOTKEY &&
                            message.wParam == static_cast<WPARAM>(kStreamHotkeyId))
                        {
                            SignalStreamClose();
                        }
                        else if (message.message == WM_QUIT)
                        {
                            running = false;
                        }
                    }
                }
                UnregisterHotKey(nullptr, kStreamHotkeyId);
            }
            else
            {
                WaitForSingleObject(self->hotkeyStopEvent_, INFINITE);
            }
            self->Release();
            return 0;
        }

        void RefreshCredential()
        {
            if ((scenario_ != CPUS_LOGON && scenario_ != CPUS_UNLOCK_WORKSTATION) ||
                !IsProviderEnabled())
            {
                ReleaseCredential();
                return;
            }
            AttemptDescriptor attempt;
            BrokerClient broker;
            if (!broker.Observe(&attempt) ||
                !ValidBrokerIdentity(attempt.account, attempt.sid) ||
                !UserArrayContainsSid(userArray_, attempt.sid))
            {
                ReleaseCredential();
                return;
            }
            if (credential_ != nullptr && currentAttempt_.SameAttempt(attempt))
            {
                return;
            }
            ReleaseCredential();
            Credential* credential = new (std::nothrow) Credential(attempt, scenario_);
            if (credential != nullptr)
            {
                credential_ = credential;
                currentAttempt_ = std::move(attempt);
            }
        }

        void ReleaseCredential()
        {
            if (credential_ != nullptr)
            {
                credential_->Release();
                credential_ = nullptr;
            }
            currentAttempt_ = AttemptDescriptor();
        }

        volatile LONG referenceCount_;
        CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario_ = CPUS_INVALID;
        Credential* credential_ = nullptr;
        AttemptDescriptor currentAttempt_;
        ICredentialProviderUserArray* userArray_ = nullptr;
        CRITICAL_SECTION eventLock_;
        ICredentialProviderEvents* events_ = nullptr;
        UINT_PTR adviseContext_ = 0;
        HANDLE stopEvent_ = nullptr;
        HANDLE hotkeyStopEvent_ = nullptr;
        HANDLE attemptEvent_ = nullptr;
        HANDLE notifierThread_ = nullptr;
        DWORD notifierThreadId_ = 0;
        HANDLE hotkeyThread_ = nullptr;
        DWORD hotkeyThreadId_ = 0;
    };

    class ClassFactory final : public IClassFactory
    {
    public:
        ClassFactory() : referenceCount_(1)
        {
            InterlockedIncrement(&g_liveObjects);
        }

        IFACEMETHODIMP QueryInterface(REFIID iid, void** object) override
        {
            if (object == nullptr)
            {
                return E_INVALIDARG;
            }
            *object = nullptr;
            if (iid == IID_IUnknown || iid == IID_IClassFactory)
            {
                *object = static_cast<IClassFactory*>(this);
                AddRef();
                return S_OK;
            }
            return E_NOINTERFACE;
        }

        IFACEMETHODIMP_(ULONG) AddRef() override
        {
            return static_cast<ULONG>(InterlockedIncrement(&referenceCount_));
        }

        IFACEMETHODIMP_(ULONG) Release() override
        {
            const LONG remaining = InterlockedDecrement(&referenceCount_);
            if (remaining == 0)
            {
                delete this;
            }
            return static_cast<ULONG>(remaining);
        }

        IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID iid,
            void** object) override
        {
            if (outer != nullptr)
            {
                return CLASS_E_NOAGGREGATION;
            }
            Provider* provider = new (std::nothrow) Provider();
            if (provider == nullptr)
            {
                return E_OUTOFMEMORY;
            }
            const HRESULT result = provider->QueryInterface(iid, object);
            provider->Release();
            return result;
        }

        IFACEMETHODIMP LockServer(BOOL lock) override
        {
            if (lock)
            {
                InterlockedIncrement(&g_serverLocks);
            }
            else
            {
                InterlockedDecrement(&g_serverLocks);
            }
            return S_OK;
        }

    private:
        ~ClassFactory()
        {
            InterlockedDecrement(&g_liveObjects);
        }

        volatile LONG referenceCount_;
    };
}

extern "C" HRESULT __stdcall DllCanUnloadNow()
{
    return g_liveObjects == 0 && g_serverLocks == 0 ? S_OK : S_FALSE;
}

extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID classId, REFIID iid,
    void** object)
{
    if (object == nullptr)
    {
        return E_INVALIDARG;
    }
    *object = nullptr;
    if (classId != CLSID_MoonWakerCredentialProvider)
    {
        return CLASS_E_CLASSNOTAVAILABLE;
    }
    ClassFactory* factory = new (std::nothrow) ClassFactory();
    if (factory == nullptr)
    {
        return E_OUTOFMEMORY;
    }
    const HRESULT result = factory->QueryInterface(iid, object);
    factory->Release();
    return result;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, void*)
{
    if (reason == DLL_PROCESS_ATTACH)
    {
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}
