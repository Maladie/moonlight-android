using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;

namespace MoonWaker.WindowsLogin
{
    internal sealed class BrokerFault : Exception
    {
        internal readonly string State;
        internal readonly string Reason;

        internal BrokerFault(string state, string reason)
            : base(reason)
        {
            State = state;
            Reason = reason;
        }
    }

    internal sealed class ProfileIdentity
    {
        internal readonly string ProfileId;
        internal readonly string Sid;
        internal readonly string AccountName;

        internal ProfileIdentity(string profileId, string sid, string accountName)
        {
            if (!IsProfileId(profileId))
                throw new BrokerFault("action_required", "invalid_profile");
            SecurityIdentifier parsed;
            try { parsed = new SecurityIdentifier(sid); }
            catch { throw new BrokerFault("action_required", "invalid_account_sid"); }
            if (parsed == null || String.IsNullOrWhiteSpace(accountName) || accountName.Length > 256)
                throw new BrokerFault("action_required", "invalid_account");
            ProfileId = profileId;
            Sid = parsed.Value;
            AccountName = accountName.Trim();
        }

        internal bool Same(ProfileIdentity other)
        {
            return other != null &&
                String.Equals(ProfileId, other.ProfileId, StringComparison.Ordinal) &&
                String.Equals(Sid, other.Sid, StringComparison.OrdinalIgnoreCase);
        }

        internal static bool IsProfileId(string value)
        {
            if (String.IsNullOrWhiteSpace(value) || value.Length > 64) return false;
            foreach (char character in value)
                if (!Char.IsLetterOrDigit(character) && character != '.' &&
                    character != '_' && character != '-') return false;
            return true;
        }
    }

    internal sealed class CredentialMaterial : IDisposable
    {
        internal readonly ProfileIdentity Identity;
        internal char[] Password;

        internal CredentialMaterial(ProfileIdentity identity, char[] password)
        {
            Identity = identity;
            Password = password == null ? new char[0] : (char[])password.Clone();
        }

        public void Dispose()
        {
            if (Password == null) return;
            Array.Clear(Password, 0, Password.Length);
            Password = null;
        }
    }

    internal interface IAccountValidator
    {
        IList<LocalAccountRecord> ListSupported();
        void Validate(ProfileIdentity identity, char[] password);
    }

    internal sealed class LocalAccountRecord
    {
        internal readonly string Sid;
        internal readonly string AccountName;

        internal LocalAccountRecord(string sid, string accountName)
        {
            Sid = sid;
            AccountName = accountName;
        }
    }

    internal interface ISecretStore
    {
        void Put(ProfileIdentity identity, char[] password);
        bool TryRead(string profileId, out CredentialMaterial credential);
        void Delete(string profileId);
    }

    internal interface ISessionStateBackend
    {
        string GetState(ProfileIdentity identity);
        string Switch(ProfileIdentity identity);
    }

    internal sealed class BrokerReply : IDisposable
    {
        internal bool Success;
        internal string State;
        internal string Reason;
        internal readonly Dictionary<byte, object> Extra = new Dictionary<byte, object>();

        internal static BrokerReply Ok(string state)
        {
            return new BrokerReply { Success = true, State = state, Reason = "none" };
        }

        internal static BrokerReply Fail(string state, string reason)
        {
            return new BrokerReply { Success = false, State = state, Reason = reason };
        }

        internal BrokerReply Add(byte key, object value)
        {
            Extra[key] = value;
            return this;
        }

        public void Dispose()
        {
            foreach (object value in Extra.Values)
            {
                char[] secret = value as char[];
                if (secret != null) Array.Clear(secret, 0, secret.Length);
                byte[] bytes = value as byte[];
                if (bytes != null) Array.Clear(bytes, 0, bytes.Length);
            }
            Extra.Clear();
        }
    }

    internal sealed class PipeRequest : IDisposable
    {
        internal readonly byte Operation;
        private readonly Dictionary<byte, byte[]> fields;

        internal PipeRequest(byte operation, Dictionary<byte, byte[]> fields)
        {
            Operation = operation;
            this.fields = fields ?? new Dictionary<byte, byte[]>();
        }

        internal string Text(byte key, int maximumCharacters)
        {
            byte[] value;
            if (!fields.TryGetValue(key, out value))
                throw new BrokerFault("action_required", "missing_field");
            string text;
            try { text = new UTF8Encoding(false, true).GetString(value); }
            catch { throw new BrokerFault("action_required", "invalid_utf8"); }
            if (String.IsNullOrWhiteSpace(text) || text.Length > maximumCharacters)
                throw new BrokerFault("action_required", "invalid_field");
            return text;
        }

        internal string OptionalText(byte key, int maximumCharacters)
        {
            byte[] value;
            if (!fields.TryGetValue(key, out value) || value.Length == 0) return "";
            string text;
            try { text = new UTF8Encoding(false, true).GetString(value); }
            catch { throw new BrokerFault("action_required", "invalid_utf8"); }
            if (text.Length > maximumCharacters)
                throw new BrokerFault("action_required", "invalid_field");
            return text;
        }

        internal char[] Secret(byte key, int maximumCharacters)
        {
            byte[] value;
            if (!fields.TryGetValue(key, out value) || value.Length == 0)
                throw new BrokerFault("action_required", "missing_password");
            UTF8Encoding encoding = new UTF8Encoding(false, true);
            char[] result;
            try
            {
                result = new char[encoding.GetCharCount(value)];
                encoding.GetChars(value, 0, value.Length, result, 0);
            }
            catch { throw new BrokerFault("action_required", "invalid_utf8"); }
            if (result.Length == 0 || result.Length > maximumCharacters)
            {
                Array.Clear(result, 0, result.Length);
                throw new BrokerFault("action_required", "invalid_password");
            }
            return result;
        }

        internal static PipeRequest FromText(byte operation, params object[] pairs)
        {
            Dictionary<byte, byte[]> values = new Dictionary<byte, byte[]>();
            for (int index = 0; index + 1 < pairs.Length; index += 2)
            {
                byte key = Convert.ToByte(pairs[index]);
                char[] secret = pairs[index + 1] as char[];
                if (secret != null)
                {
                    values[key] = Encoding.UTF8.GetBytes(secret);
                    continue;
                }
                values[key] = Encoding.UTF8.GetBytes(Convert.ToString(pairs[index + 1]));
            }
            return new PipeRequest(operation, values);
        }

        public void Dispose()
        {
            foreach (byte[] value in fields.Values) Array.Clear(value, 0, value.Length);
            fields.Clear();
        }
    }

    internal static class PipeProtocol
    {
        internal const byte Version = 1;
        internal const int MaximumFieldBytes = 4096;
        internal const int MaximumMessageBytes = 32768;
        internal const int MaximumFields = 8;
        private static readonly byte[] RequestMagic = Encoding.ASCII.GetBytes("MWLB");
        private static readonly byte[] ResponseMagic = Encoding.ASCII.GetBytes("MWLR");

        internal static PipeRequest ReadRequest(Stream stream, int timeoutMilliseconds)
        {
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(timeoutMilliseconds);
            byte[] header = Read(stream, 8, deadline);
            try
            {
                if (!Equal(header, 0, RequestMagic) || header[4] != Version ||
                    header[6] > MaximumFields || header[7] != 0)
                    throw new BrokerFault("action_required", "invalid_protocol");
                int total = 8;
                Dictionary<byte, byte[]> fields = new Dictionary<byte, byte[]>();
                try
                {
                    for (int index = 0; index < header[6]; index++)
                    {
                        byte[] fieldHeader = Read(stream, 5, deadline);
                        int length;
                        byte key;
                        try
                        {
                            key = fieldHeader[0];
                            length = ReadInt32(fieldHeader, 1);
                        }
                        finally { Array.Clear(fieldHeader, 0, fieldHeader.Length); }
                        if (length < 0 || length > MaximumFieldBytes || total + 5 + length > MaximumMessageBytes)
                            throw new BrokerFault("action_required", "message_too_large");
                        if (fields.ContainsKey(key))
                            throw new BrokerFault("action_required", "duplicate_field");
                        fields[key] = Read(stream, length, deadline);
                        total += 5 + length;
                    }
                    return new PipeRequest(header[5], fields);
                }
                catch
                {
                    foreach (byte[] value in fields.Values) Array.Clear(value, 0, value.Length);
                    throw;
                }
            }
            finally { Array.Clear(header, 0, header.Length); }
        }

        internal static void WriteReply(Stream stream, BrokerReply reply, int timeoutMilliseconds)
        {
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(timeoutMilliseconds);
            Dictionary<byte, object> fields = new Dictionary<byte, object>();
            fields[1] = reply.State ?? (reply.Success ? "ready" : "action_required");
            fields[2] = reply.Reason ?? (reply.Success ? "none" : "action_required");
            foreach (KeyValuePair<byte, object> extra in reply.Extra) fields[extra.Key] = extra.Value;
            if (fields.Count > MaximumFields) throw new InvalidDataException("Too many response fields.");
            byte[] header = new byte[8];
            Buffer.BlockCopy(ResponseMagic, 0, header, 0, ResponseMagic.Length);
            header[4] = Version;
            header[5] = reply.Success ? (byte)0 : (byte)1;
            header[6] = checked((byte)fields.Count);
            Write(stream, header, deadline);
            Array.Clear(header, 0, header.Length);
            int total = 8;
            foreach (KeyValuePair<byte, object> field in fields)
            {
                byte[] value = Encode(field.Value);
                try
                {
                    if (value.Length > MaximumFieldBytes || total + 5 + value.Length > MaximumMessageBytes)
                        throw new InvalidDataException("Response is too large.");
                    byte[] fieldHeader = new byte[5];
                    fieldHeader[0] = field.Key;
                    WriteInt32(fieldHeader, 1, value.Length);
                    Write(stream, fieldHeader, deadline);
                    Array.Clear(fieldHeader, 0, fieldHeader.Length);
                    Write(stream, value, deadline);
                    total += 5 + value.Length;
                }
                finally { Array.Clear(value, 0, value.Length); }
            }
            stream.Flush();
        }

        private static byte[] Encode(object value)
        {
            char[] secret = value as char[];
            if (secret != null)
            {
                byte[] bytes = new byte[Encoding.UTF8.GetByteCount(secret, 0, secret.Length)];
                Encoding.UTF8.GetBytes(secret, 0, secret.Length, bytes, 0);
                return bytes;
            }
            return Encoding.UTF8.GetBytes(Convert.ToString(value));
        }

        private static byte[] Read(Stream stream, int count, DateTime deadline)
        {
            byte[] result = new byte[count];
            int offset = 0;
            while (offset < count)
            {
                IAsyncResult pending = stream.BeginRead(result, offset, count - offset, null, null);
                int remaining = Math.Max(0, (int)(deadline - DateTime.UtcNow).TotalMilliseconds);
                if (remaining == 0 || !pending.AsyncWaitHandle.WaitOne(remaining))
                    throw new TimeoutException();
                int read = stream.EndRead(pending);
                if (read <= 0) throw new EndOfStreamException();
                offset += read;
            }
            return result;
        }

        private static void Write(Stream stream, byte[] value, DateTime deadline)
        {
            if (value.Length == 0) return;
            IAsyncResult pending = stream.BeginWrite(value, 0, value.Length, null, null);
            int remaining = Math.Max(0, (int)(deadline - DateTime.UtcNow).TotalMilliseconds);
            if (remaining == 0 || !pending.AsyncWaitHandle.WaitOne(remaining))
                throw new TimeoutException();
            stream.EndWrite(pending);
        }

        private static bool Equal(byte[] value, int offset, byte[] expected)
        {
            if (value.Length - offset < expected.Length) return false;
            for (int index = 0; index < expected.Length; index++)
                if (value[offset + index] != expected[index]) return false;
            return true;
        }

        internal static void WriteInt32(byte[] value, int offset, int number)
        {
            value[offset] = (byte)number;
            value[offset + 1] = (byte)(number >> 8);
            value[offset + 2] = (byte)(number >> 16);
            value[offset + 3] = (byte)(number >> 24);
        }

        internal static int ReadInt32(byte[] value, int offset)
        {
            return value[offset] | (value[offset + 1] << 8) |
                (value[offset + 2] << 16) | (value[offset + 3] << 24);
        }
    }

    internal sealed class AttemptRecord
    {
        internal string Id;
        internal string ClientId;
        internal string RequestId;
        internal ProfileIdentity Identity;
        internal DateTime CreatedUtc;
        internal string State;
        internal string Reason;
    }

    internal sealed class AttemptLedger
    {
        private readonly object gate = new object();
        private readonly Dictionary<string, AttemptRecord> byId =
            new Dictionary<string, AttemptRecord>(StringComparer.Ordinal);
        private readonly Dictionary<string, string> byRequest =
            new Dictionary<string, string>(StringComparer.Ordinal);
        private readonly Func<DateTime> utcNow;
        private readonly TimeSpan lifetime;
        private readonly int maximumAttempts;
        private readonly Action attemptCreated;

        internal AttemptLedger(Func<DateTime> utcNow, TimeSpan lifetime, int maximumAttempts)
            : this(utcNow, lifetime, maximumAttempts, null)
        {
        }

        internal AttemptLedger(Func<DateTime> utcNow, TimeSpan lifetime, int maximumAttempts,
            Action attemptCreated)
        {
            if (utcNow == null) throw new ArgumentNullException("utcNow");
            if (lifetime <= TimeSpan.Zero || maximumAttempts < 1) throw new ArgumentOutOfRangeException();
            this.utcNow = utcNow;
            this.lifetime = lifetime;
            this.maximumAttempts = maximumAttempts;
            this.attemptCreated = attemptCreated;
        }

        internal AttemptRecord Begin(string clientId, string requestId, ProfileIdentity identity)
        {
            ValidateBinding(clientId, requestId, identity);
            lock (gate)
            {
                DateTime now = utcNow();
                Expire(now);
                PurgeOld(now);
                string requestKey = Key(clientId, identity.ProfileId, requestId);
                string existingId;
                AttemptRecord existing;
                if (byRequest.TryGetValue(requestKey, out existingId) &&
                    byId.TryGetValue(existingId, out existing)) return Copy(existing);
                if (byId.Count >= maximumAttempts)
                    throw new BrokerFault("busy", "too_many_attempts");
                AttemptRecord record = new AttemptRecord {
                    Id = Guid.NewGuid().ToString("N"), ClientId = clientId,
                    RequestId = requestId, Identity = identity, CreatedUtc = now,
                    State = "pending", Reason = "none"
                };
                byId[record.Id] = record;
                byRequest[requestKey] = record.Id;
                if (attemptCreated != null) attemptCreated();
                return Copy(record);
            }
        }

        internal AttemptRecord AttentionRequired(string clientId, string requestId,
            ProfileIdentity identity, string reason)
        {
            ValidateBinding(clientId, requestId, identity);
            lock (gate)
            {
                DateTime now = utcNow();
                Expire(now);
                PurgeOld(now);
                string requestKey = Key(clientId, identity.ProfileId, requestId);
                string existingId;
                AttemptRecord existing;
                if (byRequest.TryGetValue(requestKey, out existingId) &&
                    byId.TryGetValue(existingId, out existing)) return Copy(existing);
                if (byId.Count >= maximumAttempts)
                    throw new BrokerFault("busy", "too_many_attempts");
                AttemptRecord record = new AttemptRecord {
                    Id = Guid.NewGuid().ToString("N"), ClientId = clientId,
                    RequestId = requestId, Identity = identity, CreatedUtc = now,
                    State = "attention_required", Reason = SafeReason(reason)
                };
                byId[record.Id] = record;
                byRequest[requestKey] = record.Id;
                return Copy(record);
            }
        }

        internal bool TryByRequest(string clientId, string requestId,
            ProfileIdentity identity, out AttemptRecord attempt)
        {
            ValidateBinding(clientId, requestId, identity);
            lock (gate)
            {
                DateTime now = utcNow();
                Expire(now);
                PurgeOld(now);
                string existingId;
                AttemptRecord existing;
                if (!byRequest.TryGetValue(Key(clientId, identity.ProfileId, requestId),
                        out existingId) || !byId.TryGetValue(existingId, out existing))
                {
                    attempt = null;
                    return false;
                }
                if (!String.Equals(existing.Identity.Sid, identity.Sid,
                        StringComparison.OrdinalIgnoreCase))
                    throw new BrokerFault("action_required", "attempt_binding_mismatch");
                attempt = Copy(existing);
                return true;
            }
        }

        internal bool TryObserve(out AttemptRecord pending)
        {
            lock (gate)
            {
                DateTime now = utcNow();
                Expire(now);
                AttemptRecord oldest = null;
                foreach (AttemptRecord record in byId.Values)
                    if (record.State == "pending" &&
                        (oldest == null || record.CreatedUtc < oldest.CreatedUtc))
                        oldest = record;
                pending = oldest == null ? null : Copy(oldest);
                return pending != null;
            }
        }

        internal AttemptRecord State(string attemptId, string clientId, string profileId, string requestId)
        {
            lock (gate)
            {
                AttemptRecord record = Find(attemptId, clientId, profileId, requestId);
                ExpireOne(record, utcNow());
                return Copy(record);
            }
        }

        internal AttemptRecord Acquire(string attemptId, string clientId, string profileId, string requestId)
        {
            lock (gate)
            {
                AttemptRecord record = Find(attemptId, clientId, profileId, requestId);
                ExpireOne(record, utcNow());
                if (record.State != "pending")
                    throw new BrokerFault("action_required", "credential_already_issued");
                record.State = "credential_issued";
                return Copy(record);
            }
        }

        internal AttemptRecord Report(string attemptId, string clientId, string profileId,
            string requestId, bool succeeded, string reason)
        {
            lock (gate)
            {
                AttemptRecord record = Find(attemptId, clientId, profileId, requestId);
                ExpireOne(record, utcNow());
                if (record.State == "completed" || record.State == "action_required")
                    return Copy(record);
                if (record.State != "credential_issued")
                    throw new BrokerFault("action_required", "credential_not_issued");
                record.State = succeeded ? "completed" : "action_required";
                record.Reason = succeeded ? "none" : SafeReason(reason);
                return Copy(record);
            }
        }

        internal AttemptRecord Cancel(string attemptId, string clientId, string profileId,
            string requestId)
        {
            lock (gate)
            {
                AttemptRecord record = Find(attemptId, clientId, profileId, requestId);
                ExpireOne(record, utcNow());
                if (record.State == "pending" || record.State == "credential_issued")
                {
                    record.State = "action_required";
                    record.Reason = "attempt_cancelled";
                }
                return Copy(record);
            }
        }

        internal void Fail(string attemptId, string reason)
        {
            lock (gate)
            {
                AttemptRecord record;
                if (!byId.TryGetValue(attemptId, out record)) return;
                record.State = "action_required";
                record.Reason = SafeReason(reason);
            }
        }

        private AttemptRecord Find(string attemptId, string clientId, string profileId, string requestId)
        {
            AttemptRecord record;
            if (String.IsNullOrWhiteSpace(attemptId) || !byId.TryGetValue(attemptId, out record) ||
                !String.Equals(record.ClientId, clientId, StringComparison.Ordinal) ||
                !String.Equals(record.Identity.ProfileId, profileId, StringComparison.Ordinal) ||
                !String.Equals(record.RequestId, requestId, StringComparison.Ordinal))
                throw new BrokerFault("action_required", "attempt_binding_mismatch");
            return record;
        }

        private void Expire(DateTime now)
        {
            foreach (AttemptRecord record in byId.Values) ExpireOne(record, now);
        }

        private void ExpireOne(AttemptRecord record, DateTime now)
        {
            if ((record.State == "pending" || record.State == "credential_issued") &&
                now - record.CreatedUtc >= lifetime)
            {
                record.State = "action_required";
                record.Reason = "attempt_expired";
            }
        }

        private void PurgeOld(DateTime now)
        {
            List<string> remove = new List<string>();
            foreach (KeyValuePair<string, AttemptRecord> item in byId)
                if (now - item.Value.CreatedUtc >= TimeSpan.FromTicks(lifetime.Ticks * 2))
                    remove.Add(item.Key);
            foreach (string id in remove)
            {
                AttemptRecord record = byId[id];
                byId.Remove(id);
                byRequest.Remove(Key(record.ClientId, record.Identity.ProfileId, record.RequestId));
            }
        }

        private static string Key(string clientId, string profileId, string requestId)
        {
            return clientId + "\n" + profileId + "\n" + requestId;
        }

        private static string SafeReason(string reason)
        {
            if (String.IsNullOrWhiteSpace(reason)) return "provider_failed";
            reason = reason.Trim();
            if (reason.Length > 80) reason = reason.Substring(0, 80);
            foreach (char character in reason)
                if (!(Char.IsLetterOrDigit(character) || character == '_' || character == '-'))
                    return "provider_failed";
            return reason;
        }

        private static void ValidateBinding(string clientId, string requestId, ProfileIdentity identity)
        {
            if (identity == null || !BoundedToken(clientId, 128) || !BoundedToken(requestId, 128))
                throw new BrokerFault("action_required", "invalid_attempt_binding");
        }

        private static bool BoundedToken(string value, int maximum)
        {
            if (String.IsNullOrWhiteSpace(value) || value.Length > maximum) return false;
            foreach (char character in value)
                if (Char.IsControl(character)) return false;
            return true;
        }

        private static AttemptRecord Copy(AttemptRecord value)
        {
            return new AttemptRecord {
                Id = value.Id, ClientId = value.ClientId, RequestId = value.RequestId,
                Identity = value.Identity, CreatedUtc = value.CreatedUtc,
                State = value.State, Reason = value.Reason
            };
        }
    }

    internal sealed class BrokerCore
    {
        internal const byte ManagementListAccounts = 1;
        internal const byte ManagementConfigure = 2;
        internal const byte ManagementTest = 3;
        internal const byte ManagementDelete = 4;
        internal const byte ManagementCredentialState = 5;
        internal const byte ManagementSessionState = 6;
        internal const byte GatewayBeginAttempt = 1;
        internal const byte GatewayAttemptState = 2;
        internal const byte GatewayCancelAttempt = 3;
        internal const byte GatewayProfileState = 4;
        internal const byte GatewayCapability = 5;
        internal const byte GatewaySwitchSession = 6;
        internal const byte ProviderObserve = 1;
        internal const byte ProviderAcquire = 2;
        internal const byte ProviderReport = 3;

        private readonly IAccountValidator accounts;
        private readonly ISecretStore secrets;
        private readonly ISessionStateBackend sessions;
        private readonly AttemptLedger attempts;
        private readonly Func<bool> providerReady;

        internal BrokerCore(IAccountValidator accounts, ISecretStore secrets,
            ISessionStateBackend sessions, AttemptLedger attempts)
            : this(accounts, secrets, sessions, attempts, delegate { return true; })
        {
        }

        internal BrokerCore(IAccountValidator accounts, ISecretStore secrets,
            ISessionStateBackend sessions, AttemptLedger attempts, Func<bool> providerReady)
        {
            this.accounts = accounts;
            this.secrets = secrets;
            this.sessions = sessions;
            this.attempts = attempts;
            this.providerReady = providerReady;
        }

        internal BrokerReply HandleManagement(PipeRequest request)
        {
            try
            {
                if (request.Operation == ManagementListAccounts)
                    return BrokerReply.Ok("ready").Add(3, AccountsJson(accounts.ListSupported()));
                ProfileIdentity identity = Identity(request, 1, 2, 3);
                if (request.Operation == ManagementConfigure)
                {
                    char[] password = request.Secret(4, 256);
                    try
                    {
                        accounts.Validate(identity, password);
                        secrets.Put(identity, password);
                        return BrokerReply.Ok("ready");
                    }
                    finally { Array.Clear(password, 0, password.Length); }
                }
                if (request.Operation == ManagementTest)
                {
                    CredentialMaterial stored;
                    if (!secrets.TryRead(identity.ProfileId, out stored))
                        return BrokerReply.Fail("action_required", "credential_missing");
                    using (stored)
                    {
                        if (!identity.Same(stored.Identity))
                            return BrokerReply.Fail("action_required", "credential_identity_mismatch");
                        accounts.Validate(identity, stored.Password);
                        return BrokerReply.Ok("ready");
                    }
                }
                if (request.Operation == ManagementDelete)
                {
                    CredentialMaterial stored;
                    if (secrets.TryRead(identity.ProfileId, out stored))
                    {
                        using (stored)
                            if (!identity.Same(stored.Identity))
                                return BrokerReply.Fail("action_required", "credential_identity_mismatch");
                    }
                    secrets.Delete(identity.ProfileId);
                    return BrokerReply.Ok("deleted");
                }
                if (request.Operation == ManagementCredentialState)
                {
                    CredentialMaterial stored;
                    if (!secrets.TryRead(identity.ProfileId, out stored))
                        return BrokerReply.Fail("action_required", "credential_missing");
                    using (stored)
                        return identity.Same(stored.Identity)
                            ? BrokerReply.Ok("ready")
                            : BrokerReply.Fail("action_required", "credential_identity_mismatch");
                }
                if (request.Operation == ManagementSessionState)
                    return BrokerReply.Ok(sessions.GetState(identity));
                return BrokerReply.Fail("unsupported", "unsupported_operation");
            }
            catch (BrokerFault fault) { return BrokerReply.Fail(fault.State, fault.Reason); }
            catch { return BrokerReply.Fail("action_required", "broker_operation_failed"); }
        }

        internal BrokerReply HandleGateway(PipeRequest request)
        {
            try
            {
                if (request.Operation == GatewayCapability)
                    return providerReady()
                        ? BrokerReply.Ok("ready").Add(3, "1")
                        : BrokerReply.Fail("unavailable", "provider_unavailable").Add(3, "0");
                if (request.Operation == GatewayProfileState)
                {
                    ProfileIdentity identity = Identity(request, 1, 2, 3);
                    string credentialState = "action_required";
                    string credentialReason = "credential_missing";
                    CredentialMaterial stored;
                    if (secrets.TryRead(identity.ProfileId, out stored))
                    {
                        using (stored)
                            if (identity.Same(stored.Identity))
                            {
                                credentialState = "ready";
                                credentialReason = "none";
                            }
                            else credentialReason = "credential_identity_mismatch";
                    }
                    return BrokerReply.Ok(sessions.GetState(identity))
                        .Add(3, credentialState).Add(4, credentialReason);
                }
                string clientId = request.Text(1, 128);
                string profileId = request.Text(2, 64);
                string requestId = request.Text(3, 128);
                if (request.Operation == GatewayBeginAttempt)
                {
                    ProfileIdentity identity = new ProfileIdentity(profileId,
                        request.Text(4, 184), request.Text(5, 256));
                    AttemptRecord existing;
                    if (attempts.TryByRequest(clientId, requestId, identity, out existing))
                        return AttemptReply(existing);
                    CredentialMaterial stored;
                    if (!secrets.TryRead(profileId, out stored))
                        return BrokerReply.Fail("action_required", "credential_missing");
                    using (stored)
                        if (!identity.Same(stored.Identity))
                            return BrokerReply.Fail("action_required", "credential_identity_mismatch");
                    AttemptRecord attempt = attempts.Begin(clientId, requestId, identity);
                    return AttemptReply(attempt);
                }
                if (request.Operation == GatewaySwitchSession)
                {
                    ProfileIdentity identity = new ProfileIdentity(profileId,
                        request.Text(4, 184), request.Text(5, 256));
                    AttemptRecord existing;
                    if (attempts.TryByRequest(clientId, requestId, identity, out existing))
                        return AttemptReply(existing);
                    if (!providerReady())
                        return BrokerReply.Fail("unavailable", "provider_unavailable");
                    string switchState = sessions.Switch(identity);
                    if (switchState == "ready") return BrokerReply.Ok("ready");
                    CredentialMaterial stored;
                    if (!secrets.TryRead(profileId, out stored))
                        return AttemptReply(attempts.AttentionRequired(
                            clientId, requestId, identity, "credential_missing"));
                    using (stored)
                        if (!identity.Same(stored.Identity))
                            return AttemptReply(attempts.AttentionRequired(
                                clientId, requestId, identity,
                                "credential_identity_mismatch"));
                    return AttemptReply(attempts.Begin(clientId, requestId, identity));
                }
                if (request.Operation == GatewayAttemptState)
                {
                    AttemptRecord attempt = attempts.State(request.Text(4, 64),
                        clientId, profileId, requestId);
                    BrokerReply reply = attempt.State == "action_required"
                        ? BrokerReply.Fail(attempt.State, attempt.Reason)
                        : BrokerReply.Ok(attempt.State);
                    return reply.Add(3, attempt.Id);
                }
                if (request.Operation == GatewayCancelAttempt)
                {
                    AttemptRecord attempt = attempts.Cancel(request.Text(4, 64),
                        clientId, profileId, requestId);
                    BrokerReply reply = attempt.State == "action_required"
                        ? BrokerReply.Fail(attempt.State, attempt.Reason)
                        : BrokerReply.Ok(attempt.State);
                    return reply.Add(3, attempt.Id);
                }
                return BrokerReply.Fail("unsupported", "unsupported_operation");
            }
            catch (BrokerFault fault) { return BrokerReply.Fail(fault.State, fault.Reason); }
            catch { return BrokerReply.Fail("action_required", "broker_operation_failed"); }
        }

        internal BrokerReply HandleProvider(PipeRequest request)
        {
            try
            {
                if (request.Operation == ProviderObserve)
                {
                    AttemptRecord pending;
                    if (!attempts.TryObserve(out pending)) return BrokerReply.Ok("idle");
                    return BrokerReply.Ok("pending")
                        .Add(3, pending.Id)
                        .Add(4, pending.ClientId)
                        .Add(5, pending.Identity.ProfileId)
                        .Add(6, pending.RequestId)
                        .Add(7, pending.Identity.Sid)
                        .Add(8, pending.Identity.AccountName);
                }
                string attemptId = request.Text(1, 64);
                string clientId = request.Text(2, 128);
                string profileId = request.Text(3, 64);
                string requestId = request.Text(4, 128);
                if (request.Operation == ProviderAcquire)
                {
                    AttemptRecord attempt = attempts.Acquire(attemptId, clientId, profileId, requestId);
                    CredentialMaterial credential = null;
                    if (!secrets.TryRead(profileId, out credential) ||
                        !attempt.Identity.Same(credential.Identity))
                    {
                        if (credential != null) credential.Dispose();
                        attempts.Fail(attemptId, "credential_missing");
                        return BrokerReply.Fail("action_required", "credential_missing");
                    }
                    using (credential)
                        return BrokerReply.Ok("credential_issued")
                            .Add(3, attempt.Identity.AccountName)
                            .Add(4, (char[])credential.Password.Clone())
                            .Add(5, attempt.Identity.Sid);
                }
                if (request.Operation == ProviderReport)
                {
                    string outcome = request.Text(5, 16);
                    bool success = String.Equals(outcome, "success", StringComparison.Ordinal);
                    if (!success && !String.Equals(outcome, "failure", StringComparison.Ordinal))
                        throw new BrokerFault("action_required", "invalid_provider_outcome");
                    AttemptRecord attempt = attempts.Report(attemptId, clientId, profileId,
                        requestId, success, request.OptionalText(6, 80));
                    if (!success && attempt.State == "action_required") secrets.Delete(profileId);
                    return attempt.State == "action_required"
                        ? BrokerReply.Fail(attempt.State, attempt.Reason)
                        : BrokerReply.Ok(attempt.State);
                }
                return BrokerReply.Fail("unsupported", "unsupported_operation");
            }
            catch (BrokerFault fault) { return BrokerReply.Fail(fault.State, fault.Reason); }
            catch { return BrokerReply.Fail("action_required", "broker_operation_failed"); }
        }

        private static ProfileIdentity Identity(PipeRequest request,
            byte profileField, byte sidField, byte accountField)
        {
            return new ProfileIdentity(request.Text(profileField, 64),
                request.Text(sidField, 184), request.Text(accountField, 256));
        }

        private static BrokerReply AttemptReply(AttemptRecord attempt)
        {
            BrokerReply reply = attempt.State == "action_required" ||
                attempt.State == "attention_required"
                ? BrokerReply.Fail(attempt.State, attempt.Reason)
                : BrokerReply.Ok(attempt.State);
            return reply.Add(3, attempt.Id);
        }

        private static string AccountsJson(IList<LocalAccountRecord> accounts)
        {
            StringBuilder result = new StringBuilder("{\"accounts\":[");
            for (int index = 0; index < accounts.Count; index++)
            {
                if (index > 0) result.Append(',');
                result.Append("{\"sid\":");
                AppendJsonString(result, accounts[index].Sid);
                result.Append(",\"account_name\":");
                AppendJsonString(result, accounts[index].AccountName);
                result.Append('}');
            }
            return result.Append("]}").ToString();
        }

        private static void AppendJsonString(StringBuilder output, string value)
        {
            output.Append('"');
            foreach (char character in value ?? "")
            {
                if (character == '"' || character == '\\') output.Append('\\').Append(character);
                else if (character < 0x20) output.Append("\\u").Append(((int)character).ToString("x4"));
                else output.Append(character);
            }
            output.Append('"');
        }
    }

    internal sealed class WindowsAccountValidator : IAccountValidator
    {
        private const int Logon32LogonInteractive = 2;
        private const int Logon32ProviderDefault = 0;
        private const int FilterNormalAccount = 2;
        private const uint UserAccountDisabled = 2;
        private const int ErrorMoreData = 234;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct UserInfo1
        {
            internal string Name;
            internal string Password;
            internal uint PasswordAge;
            internal uint Privilege;
            internal string HomeDirectory;
            internal string Comment;
            internal uint Flags;
            internal string ScriptPath;
        }

        [DllImport("Netapi32.dll", CharSet = CharSet.Unicode)]
        private static extern int NetUserEnum(string serverName, int level, int filter,
            out IntPtr buffer, int preferredMaximumLength, out int entriesRead,
            out int totalEntries, ref int resumeHandle);

        [DllImport("Netapi32.dll")]
        private static extern int NetApiBufferFree(IntPtr buffer);

        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool LogonUser(string userName, string domain, IntPtr password,
            int logonType, int logonProvider, out IntPtr token);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        public IList<LocalAccountRecord> ListSupported()
        {
            List<LocalAccountRecord> result = new List<LocalAccountRecord>();
            int resume = 0;
            int status;
            do
            {
                IntPtr buffer;
                int entriesRead;
                int totalEntries;
                status = NetUserEnum(null, 1, FilterNormalAccount, out buffer, -1,
                    out entriesRead, out totalEntries, ref resume);
                if (status != 0 && status != ErrorMoreData) throw new Win32Exception(status);
                try
                {
                    int size = Marshal.SizeOf(typeof(UserInfo1));
                    for (int index = 0; index < entriesRead; index++)
                    {
                        UserInfo1 user = (UserInfo1)Marshal.PtrToStructure(
                            IntPtr.Add(buffer, index * size), typeof(UserInfo1));
                        if ((user.Flags & UserAccountDisabled) != 0 || String.IsNullOrWhiteSpace(user.Name))
                            continue;
                        string account = Environment.MachineName + "\\" + user.Name;
                        try
                        {
                            SecurityIdentifier sid = (SecurityIdentifier)new NTAccount(account).Translate(
                                typeof(SecurityIdentifier));
                            result.Add(new LocalAccountRecord(sid.Value, account));
                        }
                        catch { }
                    }
                }
                finally { if (buffer != IntPtr.Zero) NetApiBufferFree(buffer); }
            } while (status == ErrorMoreData);
            result.Sort(delegate(LocalAccountRecord left, LocalAccountRecord right) {
                return StringComparer.CurrentCultureIgnoreCase.Compare(
                    left.AccountName, right.AccountName);
            });
            return result;
        }

        public void Validate(ProfileIdentity identity, char[] password)
        {
            if (identity == null || password == null || password.Length == 0)
                throw new BrokerFault("action_required", "invalid_credentials");
            int separator = identity.AccountName.IndexOf('\\');
            if (separator <= 0 || separator == identity.AccountName.Length - 1)
                throw new BrokerFault("unsupported", "local_account_required");
            string domain = identity.AccountName.Substring(0, separator);
            string user = identity.AccountName.Substring(separator + 1);
            if (!String.Equals(domain, Environment.MachineName, StringComparison.OrdinalIgnoreCase))
                throw new BrokerFault("unsupported", "local_account_required");
            SecurityIdentifier translated;
            try
            {
                translated = (SecurityIdentifier)new NTAccount(domain, user).Translate(
                    typeof(SecurityIdentifier));
            }
            catch { throw new BrokerFault("action_required", "account_not_found"); }
            if (!String.Equals(translated.Value, identity.Sid, StringComparison.OrdinalIgnoreCase))
                throw new BrokerFault("action_required", "account_sid_mismatch");

            IntPtr secret = Marshal.AllocHGlobal((password.Length + 1) * 2);
            IntPtr token = IntPtr.Zero;
            try
            {
                Marshal.Copy(password, 0, secret, password.Length);
                Marshal.WriteInt16(secret, password.Length * 2, 0);
                if (!LogonUser(user, domain, secret, Logon32LogonInteractive,
                        Logon32ProviderDefault, out token))
                    throw new BrokerFault("action_required", "invalid_credentials");
            }
            finally
            {
                for (int index = 0; index <= password.Length; index++)
                    Marshal.WriteInt16(secret, index * 2, 0);
                Marshal.FreeHGlobal(secret);
                if (token != IntPtr.Zero) CloseHandle(token);
            }
        }
    }

    internal sealed class LsaSecretStore : ISecretStore
    {
        private const uint PolicyGetPrivateInformation = 0x00000004;
        private const uint PolicyCreateSecret = 0x00000020;

        [StructLayout(LayoutKind.Sequential)]
        private struct LsaObjectAttributes
        {
            internal int Length;
            internal IntPtr RootDirectory;
            internal IntPtr ObjectName;
            internal uint Attributes;
            internal IntPtr SecurityDescriptor;
            internal IntPtr SecurityQualityOfService;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct LsaUnicodeString
        {
            internal ushort Length;
            internal ushort MaximumLength;
            internal IntPtr Buffer;
        }

        [DllImport("advapi32.dll")]
        private static extern uint LsaOpenPolicy(IntPtr systemName,
            ref LsaObjectAttributes attributes, uint access, out IntPtr policy);
        [DllImport("advapi32.dll")]
        private static extern uint LsaStorePrivateData(IntPtr policy,
            ref LsaUnicodeString key, ref LsaUnicodeString value);
        [DllImport("advapi32.dll", EntryPoint = "LsaStorePrivateData")]
        private static extern uint LsaDeletePrivateData(IntPtr policy,
            ref LsaUnicodeString key, IntPtr value);
        [DllImport("advapi32.dll")]
        private static extern uint LsaRetrievePrivateData(IntPtr policy,
            ref LsaUnicodeString key, out IntPtr value);
        [DllImport("advapi32.dll")]
        private static extern uint LsaClose(IntPtr handle);
        [DllImport("advapi32.dll")]
        private static extern uint LsaFreeMemory(IntPtr buffer);
        [DllImport("advapi32.dll")]
        private static extern int LsaNtStatusToWinError(uint status);

        public void Put(ProfileIdentity identity, char[] password)
        {
            char[] payload = Encode(identity, password);
            try
            {
                WithPolicy(PolicyCreateSecret, delegate(IntPtr policy) {
                    using (NativeUnicode key = NativeUnicode.FromString(Key(identity.ProfileId)))
                    using (NativeUnicode value = NativeUnicode.FromChars(payload))
                        Check(LsaStorePrivateData(policy, ref key.Value, ref value.Value));
                });
            }
            finally { Array.Clear(payload, 0, payload.Length); }
        }

        public bool TryRead(string profileId, out CredentialMaterial credential)
        {
            CredentialMaterial read = null;
            bool found = false;
            WithPolicy(PolicyGetPrivateInformation, delegate(IntPtr policy) {
                using (NativeUnicode key = NativeUnicode.FromString(Key(profileId)))
                {
                    IntPtr returned;
                    uint status = LsaRetrievePrivateData(policy, ref key.Value, out returned);
                    if (status != 0 && LsaNtStatusToWinError(status) == 2) return;
                    Check(status);
                    char[] payload = null;
                    try
                    {
                        LsaUnicodeString value = (LsaUnicodeString)Marshal.PtrToStructure(
                            returned, typeof(LsaUnicodeString));
                        payload = new char[value.Length / 2];
                        if (payload.Length > 0) Marshal.Copy(value.Buffer, payload, 0, payload.Length);
                        read = Decode(profileId, payload);
                        found = true;
                        for (int index = 0; index < payload.Length; index++)
                            Marshal.WriteInt16(value.Buffer, index * 2, 0);
                    }
                    finally
                    {
                        if (payload != null) Array.Clear(payload, 0, payload.Length);
                        if (returned != IntPtr.Zero) LsaFreeMemory(returned);
                    }
                }
            });
            credential = read;
            return found;
        }

        public void Delete(string profileId)
        {
            WithPolicy(PolicyCreateSecret, delegate(IntPtr policy) {
                using (NativeUnicode key = NativeUnicode.FromString(Key(profileId)))
                {
                    uint status = LsaDeletePrivateData(policy, ref key.Value, IntPtr.Zero);
                    if (status != 0 && LsaNtStatusToWinError(status) != 2) Check(status);
                }
            });
        }

        private static string Key(string profileId)
        {
            if (!ProfileIdentity.IsProfileId(profileId))
                throw new BrokerFault("action_required", "invalid_profile");
            return "L$MoonWaker.Login." + profileId;
        }

        private static char[] Encode(ProfileIdentity identity, char[] password)
        {
            string header = "MW1:" + identity.Sid.Length + ":" + identity.AccountName.Length +
                ":" + password.Length + ":";
            char[] result = new char[header.Length + identity.Sid.Length +
                identity.AccountName.Length + password.Length];
            header.CopyTo(0, result, 0, header.Length);
            identity.Sid.CopyTo(0, result, header.Length, identity.Sid.Length);
            identity.AccountName.CopyTo(0, result, header.Length + identity.Sid.Length,
                identity.AccountName.Length);
            Array.Copy(password, 0, result, header.Length + identity.Sid.Length +
                identity.AccountName.Length, password.Length);
            return result;
        }

        private static CredentialMaterial Decode(string profileId, char[] payload)
        {
            int cursor = 0;
            string version = ReadPart(payload, ref cursor);
            int sidLength = ParseLength(ReadPart(payload, ref cursor));
            int accountLength = ParseLength(ReadPart(payload, ref cursor));
            int passwordLength = ParseLength(ReadPart(payload, ref cursor));
            if (version != "MW1" || sidLength > 184 || accountLength > 256 ||
                passwordLength > 256 || cursor + sidLength + accountLength + passwordLength != payload.Length)
                throw new InvalidDataException("Invalid MoonWaker LSA credential payload.");
            string sid = new string(payload, cursor, sidLength); cursor += sidLength;
            string account = new string(payload, cursor, accountLength); cursor += accountLength;
            char[] password = new char[passwordLength];
            Array.Copy(payload, cursor, password, 0, passwordLength);
            try { return new CredentialMaterial(new ProfileIdentity(profileId, sid, account), password); }
            finally { Array.Clear(password, 0, password.Length); }
        }

        private static string ReadPart(char[] value, ref int cursor)
        {
            int start = cursor;
            while (cursor < value.Length && value[cursor] != ':') cursor++;
            if (cursor >= value.Length) throw new InvalidDataException("Invalid LSA payload header.");
            string result = new string(value, start, cursor - start);
            cursor++;
            return result;
        }

        private static int ParseLength(string value)
        {
            int result;
            if (!Int32.TryParse(value, out result) || result < 0)
                throw new InvalidDataException("Invalid LSA payload length.");
            return result;
        }

        private static void WithPolicy(uint access, Action<IntPtr> action)
        {
            LsaObjectAttributes attributes = new LsaObjectAttributes();
            attributes.Length = Marshal.SizeOf(typeof(LsaObjectAttributes));
            IntPtr policy;
            Check(LsaOpenPolicy(IntPtr.Zero, ref attributes, access, out policy));
            try { action(policy); }
            finally { if (policy != IntPtr.Zero) LsaClose(policy); }
        }

        private static void Check(uint status)
        {
            if (status != 0) throw new Win32Exception(LsaNtStatusToWinError(status));
        }

        private sealed class NativeUnicode : IDisposable
        {
            internal LsaUnicodeString Value;

            internal static NativeUnicode FromString(string value)
            {
                return FromChars(value.ToCharArray());
            }

            internal static NativeUnicode FromChars(char[] value)
            {
                NativeUnicode result = new NativeUnicode();
                result.Value.Buffer = Marshal.AllocHGlobal((value.Length + 1) * 2);
                Marshal.Copy(value, 0, result.Value.Buffer, value.Length);
                Marshal.WriteInt16(result.Value.Buffer, value.Length * 2, 0);
                result.Value.Length = checked((ushort)(value.Length * 2));
                result.Value.MaximumLength = checked((ushort)((value.Length + 1) * 2));
                return result;
            }

            public void Dispose()
            {
                if (Value.Buffer == IntPtr.Zero) return;
                for (int index = 0; index < Value.MaximumLength / 2; index++)
                    Marshal.WriteInt16(Value.Buffer, index * 2, 0);
                Marshal.FreeHGlobal(Value.Buffer);
                Value.Buffer = IntPtr.Zero;
                Value.Length = 0;
                Value.MaximumLength = 0;
            }
        }
    }
}
