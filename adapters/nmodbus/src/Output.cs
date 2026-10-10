// SPDX-License-Identifier: MIT

using System;
using System.IO;
using System.Text;
using System.Text.Json;

namespace ModbusInterop.NModbusAdapter
{
    /// <summary>
    /// stdout carries JSON only. The process keeps the real standard output
    /// for itself and points Console.Out at stderr, so nothing a library
    /// prints can end up between two documents.
    /// </summary>
    internal static class Output
    {
        private static readonly object Gate = new object();
        private static readonly Stream Stdout = Console.OpenStandardOutput();
        private static readonly JsonWriterOptions Options = new JsonWriterOptions { Indented = false };

        public static void Claim()
        {
            Console.SetOut(Console.Error);
        }

        /// <summary>Writes one JSON object followed by a newline, atomically.</summary>
        public static void Line(Action<Utf8JsonWriter> body)
        {
            using var buffer = new MemoryStream(256);
            using (var writer = new Utf8JsonWriter(buffer, Options))
            {
                writer.WriteStartObject();
                body(writer);
                writer.WriteEndObject();
            }
            buffer.WriteByte((byte)'\n');
            lock (Gate)
            {
                Stdout.Write(buffer.GetBuffer(), 0, (int)buffer.Length);
                Stdout.Flush();
            }
        }

        public static string Text(Action<Utf8JsonWriter> body)
        {
            using var buffer = new MemoryStream(256);
            using (var writer = new Utf8JsonWriter(buffer, Options))
            {
                writer.WriteStartObject();
                body(writer);
                writer.WriteEndObject();
            }
            return Encoding.UTF8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length);
        }

        public static void Raw(byte[] bytes)
        {
            lock (Gate)
            {
                Stdout.Write(bytes, 0, bytes.Length);
                Stdout.Flush();
            }
        }

        public static void Diagnostic(string message)
        {
            Console.Error.WriteLine("modbus-interop: " + message);
        }
    }

    /// <summary>A usage error: exit 2, nothing was sent.</summary>
    internal sealed class UsageException : Exception
    {
        public UsageException(string message)
            : base(message)
        {
        }
    }

    /// <summary>The logger handed to NModbus: stderr, warnings and above unless asked for more.</summary>
    internal sealed class StderrLogger : NModbus.IModbusLogger
    {
        private readonly NModbus.LoggingLevel _minimum;

        public StderrLogger()
        {
            string? wanted = Environment.GetEnvironmentVariable("MODBUS_INTEROP_LOG");
            if (wanted == null || !Enum.TryParse(wanted, true, out _minimum))
            {
                _minimum = NModbus.LoggingLevel.Warning;
            }
        }

        public void Log(NModbus.LoggingLevel level, string message)
        {
            if (ShouldLog(level))
            {
                Console.Error.WriteLine("nmodbus[" + level + "]: " + message);
            }
        }

        public bool ShouldLog(NModbus.LoggingLevel level)
        {
            return level >= _minimum;
        }
    }
}
