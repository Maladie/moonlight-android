#include "BrokerPipe.h"

#include <iostream>
#include <string>
#include <thread>
#include <vector>

namespace
{
    struct Handle
    {
        HANDLE value = INVALID_HANDLE_VALUE;
        ~Handle() { if (value != INVALID_HANDLE_VALUE && value != nullptr) CloseHandle(value); }
        Handle() = default;
        Handle(const Handle&) = delete;
        Handle& operator=(const Handle&) = delete;
    };

    std::wstring PipeName()
    {
        static unsigned counter = 0;
        return L"\\\\.\\pipe\\MoonWaker.CredentialProvider.Test." +
            std::to_wstring(GetCurrentProcessId()) + L"." + std::to_wstring(++counter);
    }

    HANDLE Server(const std::wstring& name)
    {
        return CreateNamedPipeW(name.c_str(), PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1, 256, 256, 0, nullptr);
    }

    struct Pair
    {
        std::wstring name = PipeName();
        Handle server;
        Handle client;
        Pair()
        {
            server.value = Server(name);
            if (server.value != INVALID_HANDLE_VALUE)
                client.value = moonwaker::ConnectBrokerPipe(name.c_str(), GetTickCount64() + 1000);
        }
        bool valid() const
        {
            return server.value != INVALID_HANDLE_VALUE && client.value != INVALID_HANDLE_VALUE;
        }
    };

    bool Expect(bool ok, const char* message)
    {
        if (!ok) std::cerr << "FAIL: " << message << std::endl;
        return ok;
    }

    bool TestMissingInstanceBudget()
    {
        const std::wstring name = PipeName();
        const ULONGLONG start = GetTickCount64();
        Handle client;
        client.value = moonwaker::ConnectBrokerPipe(name.c_str(), start + 100);
        const ULONGLONG elapsed = GetTickCount64() - start;
        return Expect(client.value == INVALID_HANDLE_VALUE, "missing server fails closed") &&
            Expect(elapsed >= 75 && elapsed < 1500,
                "missing instance uses the connection budget instead of failing immediately");
    }

    bool TestInstanceAppearsDuringBudget()
    {
        const std::wstring name = PipeName();
        Handle done;
        done.value = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!Expect(done.value != nullptr, "create client completion event")) return false;
        Handle client;
        std::thread connect([&] {
            client.value = moonwaker::ConnectBrokerPipe(name.c_str(), GetTickCount64() + 1000);
            SetEvent(done.value);
        });
        const DWORD early = WaitForSingleObject(done.value, 50);
        Handle server;
        server.value = Server(name);
        const DWORD completed = WaitForSingleObject(done.value, 2000);
        connect.join();
        return Expect(early == WAIT_TIMEOUT, "client waits across the server recreation gap") &&
            Expect(completed == WAIT_OBJECT_0 && client.value != INVALID_HANDLE_VALUE,
                "client connects when a new instance appears within its budget");
    }

    bool TestBusyInstanceBudget()
    {
        Pair pair;
        if (!Expect(pair.valid(), "create occupied pipe")) return false;
        const ULONGLONG start = GetTickCount64();
        Handle second;
        second.value = moonwaker::ConnectBrokerPipe(pair.name.c_str(), start + 100);
        const ULONGLONG elapsed = GetTickCount64() - start;
        return Expect(second.value == INVALID_HANDLE_VALUE && elapsed >= 75 && elapsed < 1500,
            "busy pipe respects the connection deadline");
    }

    bool TestRoundTrip()
    {
        Pair pair;
        if (!Expect(pair.valid(), "create round-trip pipe")) return false;
        const BYTE request[] = {1, 2, 3};
        BYTE received[3] = {};
        const ULONGLONG deadline = GetTickCount64() + 1000;
        return Expect(moonwaker::WriteBrokerPipe(pair.client.value, request, 3, deadline) &&
            moonwaker::ReadBrokerPipe(pair.server.value, received, 3, deadline) &&
            received[0] == 1 && received[1] == 2 && received[2] == 3,
            "bounded pipe preserves successful request bytes");
    }

    bool TestReadCancellation(bool partial)
    {
        Pair pair;
        if (!Expect(pair.valid(), "create stalled-read pipe")) return false;
        const BYTE first = 42;
        if (partial && !moonwaker::WriteBrokerPipe(pair.server.value, &first, 1,
                GetTickCount64() + 1000)) return false;
        BYTE received[2] = {};
        const ULONGLONG start = GetTickCount64();
        const bool read = moonwaker::ReadBrokerPipe(pair.client.value, received, 2, start + 100);
        const DWORD error = GetLastError();
        const ULONGLONG elapsed = GetTickCount64() - start;
        if (!Expect(!read && error == ERROR_TIMEOUT && elapsed >= 75 && elapsed < 1500,
                partial ? "partial reply cannot extend deadline" : "silent server read times out"))
            return false;
        // A cancelled read must be fully retired before its buffer is reused.
        const BYTE next = 99;
        return Expect(moonwaker::WriteBrokerPipe(pair.server.value, &next, 1,
                GetTickCount64() + 1000) &&
            moonwaker::ReadBrokerPipe(pair.client.value, received, 1,
                GetTickCount64() + 1000) && received[0] == next,
            "cancelled read cannot consume a later byte or retain its stack buffer");
    }

    bool TestWriteCancellation()
    {
        Pair pair;
        if (!Expect(pair.valid(), "create stalled-write pipe")) return false;
        const std::vector<BYTE> bytes(65536, 7);
        const ULONGLONG start = GetTickCount64();
        const bool wrote = moonwaker::WriteBrokerPipe(pair.client.value, bytes.data(),
            static_cast<DWORD>(bytes.size()), start + 100);
        const DWORD error = GetLastError();
        const ULONGLONG elapsed = GetTickCount64() - start;
        return Expect(!wrote && error == ERROR_TIMEOUT && elapsed >= 75 && elapsed < 1500,
            "full outbound pipe cancels within deadline");
    }

    bool TestExpiredDeadlineSendsNothing()
    {
        Pair pair;
        if (!Expect(pair.valid(), "create expired-deadline pipe")) return false;
        BYTE byte = 1;
        return Expect(!moonwaker::WriteBrokerPipe(pair.client.value, &byte, 1,
                GetTickCount64()) && GetLastError() == ERROR_TIMEOUT &&
            !moonwaker::ReadBrokerPipe(pair.server.value, &byte, 1,
                GetTickCount64() + 100) && GetLastError() == ERROR_TIMEOUT,
            "expired deadline sends no request bytes");
    }
}

bool TestBrokerPipe()
{
    const bool ok = TestMissingInstanceBudget() && TestInstanceAppearsDuringBudget() &&
        TestBusyInstanceBudget() && TestRoundTrip() && TestReadCancellation(false) &&
        TestReadCancellation(true) && TestWriteCancellation() && TestExpiredDeadlineSendsNothing();
    if (ok) std::cout << "PASS: 8 real named-pipe deadline and cancellation tests" << std::endl;
    return ok;
}
