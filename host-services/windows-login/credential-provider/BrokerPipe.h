#pragma once

#include <windows.h>

namespace moonwaker
{
    // Handles returned here support overlapped I/O. Callers authenticate the
    // server before sending any request, and never replay a transmitted request.
    HANDLE ConnectBrokerPipe(const wchar_t* name, ULONGLONG deadline);
    bool ReadBrokerPipe(HANDLE pipe, BYTE* bytes, DWORD count, ULONGLONG deadline);
    bool WriteBrokerPipe(HANDLE pipe, const BYTE* bytes, DWORD count, ULONGLONG deadline);
}
