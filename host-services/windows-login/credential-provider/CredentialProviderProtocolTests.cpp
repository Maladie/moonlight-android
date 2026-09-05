#include "BrokerProtocol.h"

#include <iostream>
#include <string>
#include <vector>

namespace
{
    void AppendUInt32(std::vector<BYTE>* bytes, DWORD value)
    {
        bytes->push_back(static_cast<BYTE>(value));
        bytes->push_back(static_cast<BYTE>(value >> 8));
        bytes->push_back(static_cast<BYTE>(value >> 16));
        bytes->push_back(static_cast<BYTE>(value >> 24));
    }

    void AddReplyField(std::vector<BYTE>* bytes, BYTE key, const std::string& value)
    {
        bytes->push_back(key);
        AppendUInt32(bytes, static_cast<DWORD>(value.size()));
        bytes->insert(bytes->end(), value.begin(), value.end());
    }

    bool Expect(bool condition, const wchar_t* message)
    {
        if (!condition)
        {
            std::wcerr << L"FAIL: " << message << std::endl;
        }
        return condition;
    }

    bool TestObserveRequest()
    {
        std::vector<BYTE> request;
        const std::vector<moonwaker::Field> fields;
        const std::vector<BYTE> expected = {'M', 'W', 'L', 'B', 1, 1, 0, 0};
        return Expect(moonwaker::BuildRequest(1, fields, &request),
                L"observe request builds") &&
            Expect(request == expected, L"observe wire bytes match broker v1");
    }

    bool TestAcquireRequest()
    {
        std::vector<moonwaker::Field> fields;
        for (BYTE key = 1; key <= 4; ++key)
        {
            moonwaker::Field field;
            field.key = key;
            field.value.push_back(static_cast<BYTE>('a' + key - 1));
            fields.push_back(field);
        }
        std::vector<BYTE> request;
        if (!Expect(moonwaker::BuildRequest(2, fields, &request),
                L"acquire request builds"))
        {
            return false;
        }
        return Expect(request.size() == 32, L"acquire request has bounded framing") &&
            Expect(request[5] == 2 && request[6] == 4,
                L"acquire operation and field count are encoded") &&
            Expect(request[8] == 1 && request[14] == 2 && request[20] == 3 &&
                request[26] == 4, L"acquire binding field keys are ordered");
    }

    bool TestReplyAndUtf8()
    {
        std::vector<BYTE> reply = {'M', 'W', 'L', 'R', 1, 0, 3, 0};
        AddReplyField(&reply, 1, "credential_issued");
        AddReplyField(&reply, 3, "PC\\gracz");
        AddReplyField(&reply, 4, "sekret");
        moonwaker::Reply parsed;
        if (!Expect(moonwaker::ParseReply(reply, &parsed) && parsed.success,
                L"valid reply parses"))
        {
            return false;
        }
        const std::vector<BYTE>* password = parsed.Find(4);
        std::vector<wchar_t> wide;
        const bool converted = password != nullptr &&
            moonwaker::Utf8ToWide(*password, &wide);
        const bool valueMatches = converted && std::wstring(wide.data()) == L"sekret";
        moonwaker::SecureClear(&wide);
        return Expect(parsed.Find(3) != nullptr, L"reply field lookup works") &&
            Expect(valueMatches, L"UTF-8 password conversion works");
    }

    bool TestMalformedReplyRejected()
    {
        std::vector<BYTE> duplicate = {'M', 'W', 'L', 'R', 1, 0, 2, 0};
        AddReplyField(&duplicate, 1, "ready");
        AddReplyField(&duplicate, 1, "again");
        moonwaker::Reply parsed;
        if (!Expect(!moonwaker::ParseReply(duplicate, &parsed),
                L"duplicate reply field is rejected"))
        {
            return false;
        }

        std::vector<BYTE> trailing = {'M', 'W', 'L', 'R', 1, 0, 0, 0, 42};
        if (!Expect(!moonwaker::ParseReply(trailing, &parsed),
                L"trailing reply bytes are rejected"))
        {
            return false;
        }

        std::vector<BYTE> oversized = {'M', 'W', 'L', 'R', 1, 0, 1, 0, 1};
        AppendUInt32(&oversized,
            static_cast<DWORD>(moonwaker::kMaximumFieldBytes + 1));
        return Expect(!moonwaker::ParseReply(oversized, &parsed),
            L"oversized reply field is rejected");
    }

    bool TestPendingAttemptNotificationPolicy()
    {
        std::wstring lastAttempt;
        if (!Expect(moonwaker::ShouldNotifyPendingAttempt(L"first", &lastAttempt),
                L"missed event is caught by first pending poll") ||
            !Expect(!moonwaker::ShouldNotifyPendingAttempt(L"first", &lastAttempt),
                L"duplicate poll does not refresh LogonUI"))
        {
            return false;
        }
        lastAttempt.clear(); // SetUserArray/Advise makes enumeration eligible again.
        if (!Expect(moonwaker::ShouldNotifyPendingAttempt(L"first", &lastAttempt),
                L"user-array readiness allows catch-up for same attempt") ||
            !Expect(!moonwaker::ShouldNotifyPendingAttempt(L"", &lastAttempt),
                L"acquired credential is not removed by an idle poll") ||
            !Expect(!moonwaker::ShouldNotifyPendingAttempt(L"", &lastAttempt),
                L"idle polls never refresh LogonUI"))
        {
            return false;
        }
        return Expect(moonwaker::ShouldNotifyPendingAttempt(L"second", &lastAttempt),
            L"subsequent authorized attempt notifies");
    }

}

int wmain()
{
    const bool ok = TestObserveRequest() && TestAcquireRequest() &&
        TestReplyAndUtf8() && TestMalformedReplyRejected() &&
        TestPendingAttemptNotificationPolicy();
    if (ok)
    {
        std::wcout << L"MoonWaker credential-provider protocol tests passed."
            << std::endl;
        return 0;
    }
    return 1;
}
