using System;
using System.Diagnostics;
using System.IO;
using System.Media;
using System.Runtime.InteropServices;
using System.Threading;

internal static class MoonWakerDiscordAudioWorker
{
    private const int FrameBytes = 48000 * 2 * 2 / 50;
    private const uint Loopback = 0x00020000;
    private const uint AutoConvertPcm = 0x80000000;
    private const uint SrcDefaultQuality = 0x08000000;
    private const uint Silent = 0x2;
    private static readonly Guid AudioClientId = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static readonly Guid CaptureClientId = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    private static readonly Guid AgileId = new Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90");

    [DllImport("ole32.dll")]
    private static extern int CoInitializeEx(IntPtr reserved, uint mode);

    [DllImport("Mmdevapi.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int ActivateAudioInterfaceAsync(
        string path, ref Guid iid, IntPtr activation,
        IActivateAudioInterfaceCompletionHandler handler,
        out IActivateAudioInterfaceAsyncOperation operation);

    [StructLayout(LayoutKind.Sequential)]
    private struct ActivationParams
    {
        public int Type;
        public uint ProcessId;
        public int Mode;
    }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct PropVariant
    {
        [FieldOffset(0)] public ushort Type;
        [FieldOffset(8)] public uint Size;
        [FieldOffset(16)] public IntPtr Data;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WaveFormat
    {
        public ushort Tag;
        public ushort Channels;
        public uint Rate;
        public uint BytesPerSecond;
        public ushort BlockAlign;
        public ushort Bits;
        public ushort Extra;
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceAsyncOperation
    {
        [PreserveSig]
        int GetActivateResult(out int result,
            [MarshalAs(UnmanagedType.IUnknown)] out object activated);
    }

    [ComVisible(true), Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceCompletionHandler
    {
        [PreserveSig]
        int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    private sealed class Completion : IActivateAudioInterfaceCompletionHandler,
        ICustomQueryInterface
    {
        internal readonly ManualResetEvent Done = new ManualResetEvent(false);
        internal int Result = unchecked((int)0x80004005);
        internal object Activated;

        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            int call = operation.GetActivateResult(out Result, out Activated);
            if (call < 0) Result = call;
            Done.Set();
            return 0;
        }

        public CustomQueryInterfaceResult GetInterface(ref Guid iid, out IntPtr pointer)
        {
            if (iid == AgileId)
            {
                pointer = Marshal.GetIUnknownForObject(this);
                return CustomQueryInterfaceResult.Handled;
            }
            pointer = IntPtr.Zero;
            return CustomQueryInterfaceResult.NotHandled;
        }
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint flags, long duration,
            long periodicity, IntPtr format, IntPtr session);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid,
            [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags,
            out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    private sealed class Capture : IDisposable
    {
        internal readonly IAudioClient Audio;
        internal readonly IAudioCaptureClient Samples;

        internal Capture(IAudioClient audio, IAudioCaptureClient samples)
        {
            Audio = audio;
            Samples = samples;
        }

        public void Dispose()
        {
            try { Audio.Stop(); } catch { }
            if (Samples != null && Marshal.IsComObject(Samples)) Marshal.ReleaseComObject(Samples);
            if (Audio != null && Marshal.IsComObject(Audio)) Marshal.ReleaseComObject(Audio);
        }
    }

    private sealed class CountingStream : Stream
    {
        internal long Bytes;
        public override bool CanRead { get { return false; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanWrite { get { return true; } }
        public override long Length { get { return Bytes; } }
        public override long Position { get { return Bytes; } set { throw new NotSupportedException(); } }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
        public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
        public override void SetLength(long value) { throw new NotSupportedException(); }
        public override void Write(byte[] buffer, int offset, int count) { Bytes += count; }
    }

    private static void Check(int result, string operation)
    {
        if (result < 0) throw new COMException(operation, result);
    }

    private static Capture OpenCapture(uint processId)
    {
        Process process = Process.GetProcessById(checked((int)processId));
        try { if (process.HasExited) throw new InvalidOperationException("Target process exited."); }
        finally { process.Dispose(); }

        ActivationParams parameters = new ActivationParams {
            Type = 1, ProcessId = processId, Mode = 0
        };
        IntPtr parametersPointer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(ActivationParams)));
        IntPtr variantPointer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(PropVariant)));
        IActivateAudioInterfaceAsyncOperation operation = null;
        object activated = null;
        try
        {
            Marshal.StructureToPtr(parameters, parametersPointer, false);
            Marshal.StructureToPtr(new PropVariant {
                Type = 65,
                Size = (uint)Marshal.SizeOf(typeof(ActivationParams)),
                Data = parametersPointer
            }, variantPointer, false);
            Completion completion = new Completion();
            Guid iid = AudioClientId;
            Check(ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref iid,
                variantPointer, completion, out operation), "Process loopback activation");
            if (!completion.Done.WaitOne(5000))
                throw new TimeoutException("Process loopback activation timed out.");
            Check(completion.Result, "Process loopback activation completion");
            activated = completion.Activated;
            IAudioClient audio = (IAudioClient)activated;

            WaveFormat format = new WaveFormat {
                Tag = 1, Channels = 2, Rate = 48000, BytesPerSecond = 192000,
                BlockAlign = 4, Bits = 16, Extra = 0
            };
            IntPtr formatPointer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WaveFormat)));
            try
            {
                Marshal.StructureToPtr(format, formatPointer, false);
                Check(audio.Initialize(0, Loopback | AutoConvertPcm | SrcDefaultQuality,
                    0, 0, formatPointer, IntPtr.Zero), "Process loopback initialize");
            }
            finally { Marshal.FreeHGlobal(formatPointer); }

            Guid captureId = CaptureClientId;
            object rawCapture;
            Check(audio.GetService(ref captureId, out rawCapture), "Process loopback capture service");
            return new Capture(audio, (IAudioCaptureClient)rawCapture);
        }
        catch
        {
            if (activated != null && Marshal.IsComObject(activated)) Marshal.ReleaseComObject(activated);
            throw;
        }
        finally
        {
            if (operation != null && Marshal.IsComObject(operation)) Marshal.ReleaseComObject(operation);
            Marshal.FreeHGlobal(variantPointer);
            Marshal.FreeHGlobal(parametersPointer);
        }
    }

    private static int Stream(uint processId, Stream output, bool countOnly, int durationMs)
    {
        using (Capture capture = OpenCapture(processId))
        using (Process target = Process.GetProcessById(checked((int)processId)))
        {
            Check(capture.Audio.Start(), "Process loopback start");
            byte[] frame = new byte[FrameBytes];
            int used = 0;
            long nonzero = 0;
            Stopwatch elapsed = Stopwatch.StartNew();
            long nextOutput = 20;
            while (!target.HasExited && (durationMs <= 0 || elapsed.ElapsedMilliseconds < durationMs))
            {
                uint packetFrames;
                Check(capture.Samples.GetNextPacketSize(out packetFrames), "Process loopback packet size");
                if (packetFrames == 0)
                {
                    if (!countOnly && elapsed.ElapsedMilliseconds >= nextOutput)
                    {
                        output.Write(frame, 0, frame.Length);
                        output.Flush();
                        Array.Clear(frame, 0, frame.Length);
                        used = 0;
                        nextOutput = elapsed.ElapsedMilliseconds + 20;
                    }
                    Thread.Sleep(4);
                    continue;
                }
                IntPtr data;
                uint flags;
                ulong devicePosition, qpcPosition;
                Check(capture.Samples.GetBuffer(out data, out packetFrames, out flags,
                    out devicePosition, out qpcPosition), "Process loopback buffer");
                try
                {
                    int packetBytes = checked((int)packetFrames * 4);
                    byte[] packet = new byte[packetBytes];
                    if ((flags & Silent) == 0 && data != IntPtr.Zero)
                        Marshal.Copy(data, packet, 0, packetBytes);
                    int offset = 0;
                    while (offset < packetBytes)
                    {
                        int amount = Math.Min(FrameBytes - used, packetBytes - offset);
                        Buffer.BlockCopy(packet, offset, frame, used, amount);
                        if (countOnly)
                            for (int index = offset; index < offset + amount; index++)
                                if (packet[index] != 0) nonzero++;
                        used += amount;
                        offset += amount;
                        if (used == FrameBytes)
                        {
                            if (!countOnly)
                            {
                                output.Write(frame, 0, frame.Length);
                                output.Flush();
                                nextOutput = elapsed.ElapsedMilliseconds + 20;
                            }
                            Array.Clear(frame, 0, frame.Length);
                            used = 0;
                        }
                    }
                }
                finally { Check(capture.Samples.ReleaseBuffer(packetFrames), "Process loopback release"); }
            }
            return !countOnly || nonzero > 0 ? 0 : 3;
        }
    }

    private static void PlayTone()
    {
        const int sampleRate = 48000;
        const int sampleCount = sampleRate * 6;
        using (MemoryStream memory = new MemoryStream())
        using (BinaryWriter writer = new BinaryWriter(memory))
        {
            writer.Write(new char[] {'R','I','F','F'}); writer.Write(36 + sampleCount * 4);
            writer.Write(new char[] {'W','A','V','E','f','m','t',' '}); writer.Write(16);
            writer.Write((short)1); writer.Write((short)2); writer.Write(sampleRate);
            writer.Write(sampleRate * 4); writer.Write((short)4); writer.Write((short)16);
            writer.Write(new char[] {'d','a','t','a'}); writer.Write(sampleCount * 4);
            for (int index = 0; index < sampleCount; index++)
            {
                short value = (short)(Math.Sin(2 * Math.PI * 997 * index / sampleRate) * 10000);
                writer.Write(value); writer.Write(value);
            }
            writer.Flush();
            memory.Position = 0;
            using (SoundPlayer player = new SoundPlayer(memory)) player.PlaySync();
        }
    }

    private static uint RequireProcessId(string[] args)
    {
        uint value;
        if (args.Length != 2 || !UInt32.TryParse(args[1], out value) || value == 0)
            throw new ArgumentException("A valid process ID is required.");
        return value;
    }

    private static int SelfTest()
    {
        string executable = Process.GetCurrentProcess().MainModule.FileName;
        using (Process tone = Process.Start(new ProcessStartInfo(executable, "--self-test-tone") {
            UseShellExecute = false, CreateNoWindow = true
        }))
        {
            Thread.Sleep(400);
            int result = Stream((uint)tone.Id, System.IO.Stream.Null, true, 4500);
            if (!tone.HasExited) tone.Kill();
            if (result != 0) return result;
        }
        using (Process silent = Process.Start(new ProcessStartInfo(executable,
            "--self-test-silent-child") { UseShellExecute = false, CreateNoWindow = true }))
        {
            Thread.Sleep(200);
            CountingStream output = new CountingStream();
            int result = Stream((uint)silent.Id, output, false, 500);
            if (!silent.HasExited) silent.Kill();
            if (result != 0 || output.Bytes < FrameBytes * 10) return 3;
        }
        Console.WriteLine("Discord audio process-loopback self-test passed.");
        return 0;
    }

    private static int Main(string[] args)
    {
        try
        {
            CoInitializeEx(IntPtr.Zero, 0);
            if (args.Length == 1 && args[0] == "--self-test-tone") { PlayTone(); return 0; }
            if (args.Length == 1 && args[0] == "--self-test-silent-child")
            { Thread.Sleep(3000); return 0; }
            if (args.Length == 1 && args[0] == "--self-test") return SelfTest();
            if (args.Length > 0 && args[0] == "--probe")
            {
                using (Capture capture = OpenCapture(RequireProcessId(args))) { }
                return 0;
            }
            if (args.Length > 0 && args[0] == "--stream")
                return Stream(RequireProcessId(args), Console.OpenStandardOutput(), false, 0);
            return 64;
        }
        catch (Exception error)
        {
            COMException com = error as COMException;
            if (com != null) Console.Error.WriteLine("unavailable 0x{0:X8}", com.ErrorCode);
            else Console.Error.WriteLine("unavailable");
            return 2;
        }
    }
}
