using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

internal static class MoonWakerMicrophoneWorker
{
    private const int FrameSamples = 960;
    private const int FrameBytes = FrameSamples * 2;
    private const int QueueFrames = 6;
    private const uint AutoConvertPcm = 0x80000000;
    private const uint SrcDefaultQuality = 0x08000000;
    private static readonly Guid AudioClientId = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static readonly Guid AudioRenderClientId = new Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
    private static readonly PropertyKey FriendlyName = new PropertyKey(
        new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), 14);

    public static int Main(string[] args)
    {
        try
        {
            if (args.Length != 1) return 2;
            if (args[0] == "--self-test") return SelfTest() ? 0 : 1;
            using (Renderer renderer = Renderer.Open())
            {
                if (args[0] == "--probe") return 0;
                if (args[0] != "--stream") return 2;
                renderer.Stream(Console.OpenStandardInput());
                return 0;
            }
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error.GetType().Name);
            return 1;
        }
    }

    private static bool SelfTest()
    {
        FrameQueue queue = new FrameQueue();
        for (byte value = 0; value < 8; value++) queue.Add(new byte[] { value });
        byte[] frame;
        return queue.TryTake(out frame) && frame[0] == 2 &&
            typeof(IMMDeviceCollection).GUID == new Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E") &&
            EndpointMatches("Speakers (Steam Streaming Microphone)") &&
            EndpointMatches("Głośniki Steam Streaming Microphone") &&
            !EndpointMatches("Steam Streaming Speakers");
    }

    internal static bool EndpointMatches(string name)
    {
        return name != null && name.IndexOf(
            "Steam Streaming Microphone", StringComparison.OrdinalIgnoreCase) >= 0;
    }

    private sealed class FrameQueue
    {
        private readonly Queue<byte[]> frames = new Queue<byte[]>();
        private bool completed;

        internal void Add(byte[] frame)
        {
            lock (frames)
            {
                if (completed) return;
                while (frames.Count >= QueueFrames) frames.Dequeue();
                frames.Enqueue(frame);
                Monitor.Pulse(frames);
            }
        }

        internal bool TryTake(out byte[] frame)
        {
            lock (frames)
            {
                while (frames.Count == 0 && !completed) Monitor.Wait(frames, 100);
                frame = frames.Count == 0 ? null : frames.Dequeue();
                return frame != null;
            }
        }

        internal void Complete()
        {
            lock (frames) { completed = true; Monitor.PulseAll(frames); }
        }
    }

    private sealed class Renderer : IDisposable
    {
        private IMMDevice device;
        private IAudioClient client;
        private IAudioRenderClient render;
        private uint bufferFrames;

        internal static Renderer Open()
        {
            Renderer owner = new Renderer();
            try { owner.Initialize(); return owner; }
            catch { owner.Dispose(); throw; }
        }

        private void Initialize()
        {
            IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDeviceCollection collection = null;
            try
            {
                Check(enumerator.EnumAudioEndpoints(DataFlow.Render, DeviceState.Active, out collection));
                uint count; Check(collection.GetCount(out count));
                for (uint index = 0; index < count; index++)
                {
                    IMMDevice candidate; Check(collection.Item(index, out candidate));
                    if (EndpointMatches(ReadName(candidate)))
                    {
                        if (device != null) { Marshal.ReleaseComObject(candidate); throw new InvalidOperationException("Ambiguous endpoint"); }
                        device = candidate;
                    }
                    else Marshal.ReleaseComObject(candidate);
                }
            }
            finally
            {
                if (collection != null) Marshal.ReleaseComObject(collection);
                Marshal.ReleaseComObject(enumerator);
            }
            if (device == null) throw new InvalidOperationException("Endpoint unavailable");
            object value; Guid id = AudioClientId;
            Check(device.Activate(ref id, 23, IntPtr.Zero, out value));
            client = (IAudioClient)value;
            WaveFormat format = new WaveFormat { FormatTag = 1, Channels = 1, SamplesPerSec = 48000,
                AvgBytesPerSec = 96000, BlockAlign = 2, BitsPerSample = 16, ExtraSize = 0 };
            Check(client.Initialize(0, AutoConvertPcm | SrcDefaultQuality, 1000000, 0, ref format, IntPtr.Zero));
            Check(client.GetBufferSize(out bufferFrames));
            if (bufferFrames < FrameSamples) throw new InvalidOperationException("WASAPI buffer is too small");
            object service; id = AudioRenderClientId;
            Check(client.GetService(ref id, out service));
            render = (IAudioRenderClient)service;
        }

        internal void Stream(Stream input)
        {
            FrameQueue queue = new FrameQueue();
            Exception readerError = null;
            Thread reader = new Thread(() =>
            {
                try
                {
                    while (true)
                    {
                        byte[] frame = new byte[FrameBytes];
                        int offset = 0;
                        while (offset < frame.Length)
                        {
                            int read = input.Read(frame, offset, frame.Length - offset);
                            if (read == 0) { if (offset != 0) throw new InvalidDataException(); return; }
                            offset += read;
                        }
                        queue.Add(frame);
                    }
                }
                catch (Exception error) { readerError = error; }
                finally { queue.Complete(); }
            });
            reader.IsBackground = true;
            reader.Start();
            Check(client.Start());
            try
            {
                byte[] frame;
                while (queue.TryTake(out frame))
                {
                    while (true)
                    {
                        uint padding; Check(client.GetCurrentPadding(out padding));
                        if (bufferFrames - padding >= FrameSamples) break;
                        Thread.Sleep(2);
                    }
                    IntPtr target; Check(render.GetBuffer(FrameSamples, out target));
                    Marshal.Copy(frame, 0, target, frame.Length);
                    Check(render.ReleaseBuffer(FrameSamples, 0));
                }
            }
            finally { client.Stop(); reader.Join(1000); }
            if (readerError != null) throw readerError;
        }

        private static string ReadName(IMMDevice value)
        {
            IPropertyStore store; Check(value.OpenPropertyStore(0, out store));
            try
            {
                PropVariant property; PropertyKey key = FriendlyName;
                Check(store.GetValue(ref key, out property));
                try
                {
                    return property.Type == 31 && property.Pointer != IntPtr.Zero
                        ? Marshal.PtrToStringUni(property.Pointer) : "";
                }
                finally { PropVariantClear(ref property); }
            }
            finally { if (store != null) Marshal.ReleaseComObject(store); }
        }

        public void Dispose()
        {
            if (render != null) Marshal.ReleaseComObject(render);
            if (client != null) Marshal.ReleaseComObject(client);
            if (device != null) Marshal.ReleaseComObject(device);
            render = null; client = null; device = null;
        }
    }

    private static void Check(int result) { if (result < 0) Marshal.ThrowExceptionForHR(result); }
    [DllImport("ole32.dll")] private static extern int PropVariantClear(ref PropVariant value);

    private enum DataFlow { Render, Capture, All }
    [Flags] private enum DeviceState : uint { Active = 1 }
    [StructLayout(LayoutKind.Sequential)] private struct PropertyKey { internal Guid Format; internal uint Id; internal PropertyKey(Guid format, uint id) { Format = format; Id = id; } }
    [StructLayout(LayoutKind.Explicit, Size = 16)] private struct PropVariant
    {
        [FieldOffset(0)] internal ushort Type;
        [FieldOffset(8)] internal IntPtr Pointer;
    }
    [StructLayout(LayoutKind.Sequential, Pack = 2)] private struct WaveFormat { internal ushort FormatTag, Channels; internal uint SamplesPerSec, AvgBytesPerSec; internal ushort BlockAlign, BitsPerSample, ExtraSize; }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] private class MMDeviceEnumerator { }
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator { [PreserveSig] int EnumAudioEndpoints(DataFlow flow, DeviceState state, out IMMDeviceCollection devices); }
    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceCollection { [PreserveSig] int GetCount(out uint count); [PreserveSig] int Item(uint index, out IMMDevice device); }
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice { [PreserveSig] int Activate(ref Guid id, uint context, IntPtr activation, [MarshalAs(UnmanagedType.IUnknown)] out object value); [PreserveSig] int OpenPropertyStore(uint access, out IPropertyStore properties); }
    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPropertyStore { [PreserveSig] int GetCount(out uint count); [PreserveSig] int GetAt(uint index, out PropertyKey key); [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value); }
    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient { [PreserveSig] int Initialize(int shareMode, uint flags, long duration, long periodicity, ref WaveFormat format, IntPtr session); [PreserveSig] int GetBufferSize(out uint frames); [PreserveSig] int GetStreamLatency(out long latency); [PreserveSig] int GetCurrentPadding(out uint padding); [PreserveSig] int IsFormatSupported(int shareMode, ref WaveFormat format, IntPtr closest); [PreserveSig] int GetMixFormat(out IntPtr format); [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod); [PreserveSig] int Start(); [PreserveSig] int Stop(); [PreserveSig] int Reset(); [PreserveSig] int SetEventHandle(IntPtr handle); [PreserveSig] int GetService(ref Guid id, [MarshalAs(UnmanagedType.IUnknown)] out object service); }
    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioRenderClient { [PreserveSig] int GetBuffer(uint frames, out IntPtr data); [PreserveSig] int ReleaseBuffer(uint frames, uint flags); }
}
