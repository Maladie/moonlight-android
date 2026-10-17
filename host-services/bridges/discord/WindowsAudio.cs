using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public sealed class WindowsAudioDeviceInfo
{
    public string Id { get; set; }
    public string Name { get; set; }
    public string Flow { get; set; }
    public bool IsDefault { get; set; }
    public bool IsDefaultCommunications { get; set; }
}

public sealed class WindowsAudioVolumeInfo
{
    public int Volume { get; set; }
    public bool Muted { get; set; }
}

public static class WindowsAudioBridge
{
    private const uint DEVICE_STATE_ACTIVE = 0x00000001;
    private static readonly Guid IID_IAudioEndpointVolume = new Guid("5CDF2C82-841E-4546-9722-0CF74078229A");
    private static readonly PROPERTYKEY PKEY_Device_FriendlyName = new PROPERTYKEY(new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), 14);

    public static WindowsAudioDeviceInfo[] GetDevices()
    {
        var result = new List<WindowsAudioDeviceInfo>();
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
        try
        {
            AddDevices(enumerator, EDataFlow.eRender, "output", result);
            AddDevices(enumerator, EDataFlow.eCapture, "input", result);
            return result.ToArray();
        }
        finally { Release(enumerator); }
    }

    public static void SetDefaultDevice(string deviceId)
    {
        if (String.IsNullOrWhiteSpace(deviceId)) throw new ArgumentException("deviceId");
        var policy = (IPolicyConfigVista)new PolicyConfigClient();
        try
        {
            Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(deviceId, ERole.eConsole));
            Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(deviceId, ERole.eMultimedia));
            Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(deviceId, ERole.eCommunications));
        }
        finally { Release(policy); }
    }

    public static string GetDefaultDeviceId(string flow, int role)
    {
        EDataFlow dataFlow = String.Equals(flow, "input", StringComparison.OrdinalIgnoreCase) ? EDataFlow.eCapture : EDataFlow.eRender;
        ERole endpointRole = role == 2 ? ERole.eCommunications : (role == 0 ? ERole.eConsole : ERole.eMultimedia);
        var device = GetDefaultDevice(dataFlow, endpointRole);
        try { return GetId(device); }
        finally { Release(device); }
    }

    public static WindowsAudioVolumeInfo GetMasterVolume()
    {
        var endpoint = GetDefaultDevice(EDataFlow.eRender, ERole.eMultimedia);
        try
        {
            var volume = ActivateVolume(endpoint);
            try
            {
                float scalar;
                bool muted;
                Marshal.ThrowExceptionForHR(volume.GetMasterVolumeLevelScalar(out scalar));
                Marshal.ThrowExceptionForHR(volume.GetMute(out muted));
                return new WindowsAudioVolumeInfo {
                    Volume = Math.Max(0, Math.Min(100, (int)Math.Round(scalar * 100.0f))),
                    Muted = muted
                };
            }
            finally { Release(volume); }
        }
        finally { Release(endpoint); }
    }

    public static WindowsAudioVolumeInfo SetMasterVolume(int volumePercent)
    {
        int safe = Math.Max(0, Math.Min(100, volumePercent));
        var endpoint = GetDefaultDevice(EDataFlow.eRender, ERole.eMultimedia);
        try
        {
            var volume = ActivateVolume(endpoint);
            try
            {
                Guid context = Guid.Empty;
                Marshal.ThrowExceptionForHR(volume.SetMasterVolumeLevelScalar(safe / 100.0f, ref context));
            }
            finally { Release(volume); }
        }
        finally { Release(endpoint); }
        return GetMasterVolume();
    }

    public static WindowsAudioVolumeInfo SetMute(bool muted)
    {
        var endpoint = GetDefaultDevice(EDataFlow.eRender, ERole.eMultimedia);
        try
        {
            var volume = ActivateVolume(endpoint);
            try
            {
                Guid context = Guid.Empty;
                Marshal.ThrowExceptionForHR(volume.SetMute(muted, ref context));
            }
            finally { Release(volume); }
        }
        finally { Release(endpoint); }
        return GetMasterVolume();
    }

    private static void AddDevices(IMMDeviceEnumerator enumerator, EDataFlow flow, string flowName, List<WindowsAudioDeviceInfo> result)
    {
        IMMDevice defaultDevice = null;
        IMMDevice communicationsDevice = null;
        string defaultId = null;
        string communicationsId = null;
        try
        {
            if (enumerator.GetDefaultAudioEndpoint(flow, ERole.eMultimedia, out defaultDevice) == 0) defaultId = GetId(defaultDevice);
            if (enumerator.GetDefaultAudioEndpoint(flow, ERole.eCommunications, out communicationsDevice) == 0) communicationsId = GetId(communicationsDevice);
        }
        finally { Release(defaultDevice); Release(communicationsDevice); }

        IMMDeviceCollection collection;
        Marshal.ThrowExceptionForHR(enumerator.EnumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, out collection));
        try
        {
            uint count;
            Marshal.ThrowExceptionForHR(collection.GetCount(out count));
            for (uint index = 0; index < count; index++)
            {
                IMMDevice device;
                Marshal.ThrowExceptionForHR(collection.Item(index, out device));
                try
                {
                    string id = GetId(device);
                    result.Add(new WindowsAudioDeviceInfo {
                        Id = id,
                        Name = GetFriendlyName(device),
                        Flow = flowName,
                        IsDefault = String.Equals(id, defaultId, StringComparison.OrdinalIgnoreCase),
                        IsDefaultCommunications = String.Equals(id, communicationsId, StringComparison.OrdinalIgnoreCase)
                    });
                }
                finally { Release(device); }
            }
        }
        finally { Release(collection); }
    }

    private static IMMDevice GetDefaultDevice(EDataFlow flow, ERole role)
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
        try
        {
            IMMDevice device;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(flow, role, out device));
            return device;
        }
        finally { Release(enumerator); }
    }

    private static IAudioEndpointVolume ActivateVolume(IMMDevice device)
    {
        object value;
        Guid iid = IID_IAudioEndpointVolume;
        Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out value));
        return (IAudioEndpointVolume)value;
    }

    private static string GetId(IMMDevice device)
    {
        string id;
        Marshal.ThrowExceptionForHR(device.GetId(out id));
        return id;
    }

    private static string GetFriendlyName(IMMDevice device)
    {
        IPropertyStore store;
        Marshal.ThrowExceptionForHR(device.OpenPropertyStore(0, out store));
        try
        {
            PROPVARIANT value;
            PROPERTYKEY key = PKEY_Device_FriendlyName;
            Marshal.ThrowExceptionForHR(store.GetValue(ref key, out value));
            try { return value.GetString() ?? "Unknown audio device"; }
            finally { PropVariantClear(ref value); }
        }
        finally { Release(store); }
    }

    private static void Release(object value)
    {
        if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value);
    }

    [DllImport("ole32.dll")]
    private static extern int PropVariantClear(ref PROPVARIANT pvar);
}

internal enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }
internal enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
internal class MMDeviceEnumeratorComObject { }

[ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")]
internal class PolicyConfigClient { }

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
internal interface IMMDeviceEnumerator
{
    [PreserveSig]
    int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IMMDeviceCollection devices);
    [PreserveSig]
    int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);
    [PreserveSig]
    int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
    [PreserveSig]
    int RegisterEndpointNotificationCallback(IntPtr client);
    [PreserveSig]
    int UnregisterEndpointNotificationCallback(IntPtr client);
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("0BD7A1BE-7A1A-44DB-8397-C0A08E7D76A7")]
internal interface IMMDeviceCollection
{
    [PreserveSig]
    int GetCount(out uint count);
    [PreserveSig]
    int Item(uint index, out IMMDevice device);
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("D666063F-1587-4E43-81F1-B948E807363F")]
internal interface IMMDevice
{
    [PreserveSig]
    int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
    [PreserveSig]
    int OpenPropertyStore(uint access, out IPropertyStore properties);
    [PreserveSig]
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    [PreserveSig]
    int GetState(out uint state);
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
internal interface IPropertyStore
{
    [PreserveSig]
    int GetCount(out uint count);
    [PreserveSig]
    int GetAt(uint propertyIndex, out PROPERTYKEY key);
    [PreserveSig]
    int GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
    [PreserveSig]
    int SetValue(ref PROPERTYKEY key, ref PROPVARIANT value);
    [PreserveSig]
    int Commit();
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("F8679F50-850A-41CF-9C72-430F290290C8")]
internal interface IPolicyConfigVista
{
    [PreserveSig]
    int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)] string deviceId, IntPtr format);
    [PreserveSig]
    int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int defaultFormat, IntPtr format);
    [PreserveSig]
    int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string deviceId);
    [PreserveSig]
    int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string deviceId, IntPtr endpointFormat, IntPtr mixFormat);
    [PreserveSig]
    int GetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int defaultPeriod, IntPtr defaultValue, IntPtr minimumValue);
    [PreserveSig]
    int SetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string deviceId, IntPtr period);
    [PreserveSig]
    int GetShareMode([MarshalAs(UnmanagedType.LPWStr)] string deviceId, IntPtr mode);
    [PreserveSig]
    int SetShareMode([MarshalAs(UnmanagedType.LPWStr)] string deviceId, IntPtr mode);
    [PreserveSig]
    int GetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int store, ref PROPERTYKEY key, out PROPVARIANT value);
    [PreserveSig]
    int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int store, ref PROPERTYKEY key, ref PROPVARIANT value);
    [PreserveSig]
    int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string deviceId, ERole role);
    [PreserveSig]
    int SetEndpointVisibility([MarshalAs(UnmanagedType.LPWStr)] string deviceId, int visible);
}

[ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
internal interface IAudioEndpointVolume
{
    [PreserveSig]
    int RegisterControlChangeNotify(IntPtr notify);
    [PreserveSig]
    int UnregisterControlChangeNotify(IntPtr notify);
    [PreserveSig]
    int GetChannelCount(out uint count);
    [PreserveSig]
    int SetMasterVolumeLevel(float levelDb, ref Guid context);
    [PreserveSig]
    int SetMasterVolumeLevelScalar(float level, ref Guid context);
    [PreserveSig]
    int GetMasterVolumeLevel(out float levelDb);
    [PreserveSig]
    int GetMasterVolumeLevelScalar(out float level);
    [PreserveSig]
    int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid context);
    [PreserveSig]
    int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
    [PreserveSig]
    int GetChannelVolumeLevel(uint channel, out float levelDb);
    [PreserveSig]
    int GetChannelVolumeLevelScalar(uint channel, out float level);
    [PreserveSig]
    int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
    [PreserveSig]
    int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    [PreserveSig]
    int GetVolumeStepInfo(out uint step, out uint stepCount);
    [PreserveSig]
    int VolumeStepUp(ref Guid context);
    [PreserveSig]
    int VolumeStepDown(ref Guid context);
    [PreserveSig]
    int QueryHardwareSupport(out uint supportMask);
    [PreserveSig]
    int GetVolumeRange(out float minDb, out float maxDb, out float incrementDb);
}

[StructLayout(LayoutKind.Sequential)]
internal struct PROPERTYKEY
{
    public Guid fmtid;
    public uint pid;
    public PROPERTYKEY(Guid formatId, uint propertyId) { fmtid = formatId; pid = propertyId; }
}

[StructLayout(LayoutKind.Explicit)]
internal struct PROPVARIANT
{
    [FieldOffset(0)] public ushort vt;
    [FieldOffset(8)] public IntPtr pointerValue;
    public string GetString() { return vt == 31 && pointerValue != IntPtr.Zero ? Marshal.PtrToStringUni(pointerValue) : null; }
}
