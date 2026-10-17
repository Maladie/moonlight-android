#include "BrokerPipe.h"

#include <algorithm>

namespace
{
    DWORD Remaining(ULONGLONG deadline)
    {
        const ULONGLONG now = GetTickCount64();
        return now >= deadline ? 0 : static_cast<DWORD>(
            (std::min)(deadline - now, static_cast<ULONGLONG>(MAXDWORD - 1)));
    }

    bool Transfer(HANDLE pipe, const BYTE* source, BYTE* destination,
        DWORD count, ULONGLONG deadline)
    {
        if (count == 0) return true;
        HANDLE event = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (event == nullptr) return false;
        DWORD offset = 0;
        DWORD error = ERROR_SUCCESS;
        while (offset < count)
        {
            if (Remaining(deadline) == 0)
            {
                error = ERROR_TIMEOUT;
                break;
            }
            ResetEvent(event);
            OVERLAPPED operation = {};
            operation.hEvent = event;
            DWORD transferred = 0;
            const BOOL started = source != nullptr
                ? WriteFile(pipe, source + offset, count - offset, nullptr, &operation)
                : ReadFile(pipe, destination + offset, count - offset, nullptr, &operation);
            if (!started && GetLastError() != ERROR_IO_PENDING)
            {
                error = GetLastError();
                break;
            }
            if (!started)
            {
                const DWORD wait = WaitForSingleObject(event, Remaining(deadline));
                if (wait != WAIT_OBJECT_0)
                {
                    error = wait == WAIT_TIMEOUT ? ERROR_TIMEOUT : GetLastError();
                    CancelIoEx(pipe, &operation);
                    // Cancellation is asynchronous. Keep the OVERLAPPED and
                    // caller's buffer alive until the kernel retires this I/O.
                    GetOverlappedResult(pipe, &operation, &transferred, TRUE);
                    break;
                }
            }
            if (!GetOverlappedResult(pipe, &operation, &transferred, FALSE))
            {
                error = GetLastError();
                break;
            }
            if (transferred == 0)
            {
                error = ERROR_BROKEN_PIPE;
                break;
            }
            offset += transferred;
        }
        CloseHandle(event);
        SetLastError(error);
        return error == ERROR_SUCCESS;
    }
}

namespace moonwaker
{
    HANDLE ConnectBrokerPipe(const wchar_t* name, ULONGLONG deadline)
    {
        DWORD remaining;
        while ((remaining = Remaining(deadline)) != 0)
        {
            HANDLE pipe = CreateFileW(name, GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                OPEN_EXISTING, FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT |
                    SECURITY_IDENTIFICATION, nullptr);
            if (pipe != INVALID_HANDLE_VALUE) return pipe;
            const DWORD error = GetLastError();
            remaining = Remaining(deadline);
            if (remaining == 0) break;
            if (error == ERROR_FILE_NOT_FOUND)
            {
                // The single-instance Broker briefly removes the name between
                // clients. WaitNamedPipe returns immediately during that gap.
                Sleep((std::min)(remaining, static_cast<DWORD>(10)));
            }
            else if (error == ERROR_PIPE_BUSY)
            {
                if (!WaitNamedPipeW(name, remaining))
                {
                    const DWORD waitError = GetLastError();
                    if (waitError == ERROR_SEM_TIMEOUT) break;
                    if (waitError != ERROR_FILE_NOT_FOUND && waitError != ERROR_PIPE_BUSY)
                        return INVALID_HANDLE_VALUE;
                }
                // Availability is not a reservation: another client may win.
            }
            else return INVALID_HANDLE_VALUE;
        }
        SetLastError(ERROR_TIMEOUT);
        return INVALID_HANDLE_VALUE;
    }

    bool ReadBrokerPipe(HANDLE pipe, BYTE* bytes, DWORD count, ULONGLONG deadline)
    {
        return Transfer(pipe, nullptr, bytes, count, deadline);
    }

    bool WriteBrokerPipe(HANDLE pipe, const BYTE* bytes, DWORD count, ULONGLONG deadline)
    {
        return Transfer(pipe, bytes, nullptr, count, deadline);
    }
}
