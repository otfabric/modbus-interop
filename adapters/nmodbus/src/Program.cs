// SPDX-License-Identifier: MIT

using System;
using System.IO;
using System.Reflection;
using System.Text.Json;
using NModbus;

namespace ModbusInterop.NModbusAdapter
{
    internal static class Program
    {
        public const string Adapter = "nmodbus";

        private const string FixtureRoot = "/fixtures";

        private const string Usage =
            "usage: modbus-interop server [--fixture PATH] [--bind-address ADDR] [--port N] [--ready-file PATH]\n" +
            "       modbus-interop client <operation> --host HOST [flags]\n" +
            "       modbus-interop print-capabilities\n" +
            "       modbus-interop print-fixture [NAME]\n" +
            "       modbus-interop version";

        public static int Main(string[] args)
        {
            Output.Claim();
            int code;
            try
            {
                code = Run(args);
            }
            catch (UsageException e)
            {
                Output.Diagnostic(e.Message);
                Console.Error.WriteLine(Usage);
                code = 2;
            }

            Console.Error.Flush();

            // Threads of the slave network must not keep the process alive.
            Environment.Exit(code);
            return code;
        }

        private static int Run(string[] args)
        {
            if (args.Length == 0)
            {
                throw new UsageException("a command is required");
            }

            switch (args[0])
            {
                case "server":
                    return Server.Run(args);
                case "client":
                    return Client.Run(args);
                case "print-capabilities":
                    NoMore(args, 1);
                    PrintCapabilities();
                    return 0;
                case "print-fixture":
                    NoMore(args, 2);
                    return PrintFixture(args.Length > 1 ? args[1] : "baseline");
                case "version":
                    NoMore(args, 1);
                    Upstream(out string version, out string revision);
                    Output.Raw(System.Text.Encoding.UTF8.GetBytes(
                        "modbus-interop " + Adapter + " " + AdapterVersion() + " (NModbus " + version + ", " + revision + ")\n"));
                    return 0;
                default:
                    throw new UsageException("unknown command '" + args[0] + "'");
            }
        }

        private static void NoMore(string[] args, int max)
        {
            if (args.Length > max)
            {
                throw new UsageException("unexpected argument '" + args[max] + "'");
            }
        }

        private static string AdapterVersion()
        {
            string? version = Environment.GetEnvironmentVariable("ADAPTER_VERSION");
            return string.IsNullOrEmpty(version) ? "dev" : version;
        }

        /// <summary>
        /// The NModbus that is actually loaded: its informational version is
        /// "version+commit", written by the upstream build.
        /// </summary>
        private static void Upstream(out string version, out string revision)
        {
            Assembly assembly = typeof(ModbusFactory).Assembly;
            string informational = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
                                   ?? assembly.GetName().Version?.ToString() ?? "unknown";
            int plus = informational.IndexOf('+');
            version = plus < 0 ? informational : informational.Substring(0, plus);
            revision = plus < 0 ? "nuget:NModbus/" + informational : informational.Substring(plus + 1);
        }

        private static int PrintFixture(string name)
        {
            if (!Fixture.IsFixtureName(name))
            {
                Output.Diagnostic("no fixture '" + name + "'");
                return 1;
            }

            try
            {
                Output.Raw(File.ReadAllBytes(Path.Combine(FixtureRoot, name, "fixture.json")));
                return 0;
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
            {
                Output.Diagnostic("no fixture '" + name + "': " + e.Message);
                return 1;
            }
        }

        private static void PrintCapabilities()
        {
            Upstream(out string version, out string revision);
            Output.Line(w =>
            {
                w.WriteString("schemaVersion", "1.0");
                w.WriteString("adapter", Adapter);
                w.WriteString("adapterVersion", AdapterVersion());
                w.WriteString("protocol", "modbus-tcp");
                w.WriteStartObject("upstream");
                w.WriteString("name", "NModbus");
                w.WriteString("version", version);
                w.WriteString("revision", revision);
                w.WriteString("license", "MIT");
                w.WriteString("language", "C#");
                w.WriteBoolean("independentEngine", true);
                w.WriteEndObject();
                w.WriteStartObject("roles");
                w.WriteBoolean("server", true);
                w.WriteBoolean("client", true);
                w.WriteEndObject();
                w.WriteStartObject("features");
                w.WriteBoolean("serverUnitIdFiltering", true);
                w.WriteBoolean("serverQuantityCheck", true);
                w.WriteBoolean("serverMaskWrite", false);
                w.WriteBoolean("serverReadWriteMultiple", true);
                w.WriteBoolean("serverDeviceIdentification", false);
                w.WriteBoolean("requestEvents", true);
                w.WriteBoolean("connectionEvents", false);
                w.WriteBoolean("clientMaskWrite", false);
                w.WriteBoolean("clientReadWriteMultiple", true);
                w.WriteBoolean("clientDeviceIdentification", true);
                w.WriteBoolean("clientRaw", true);
                w.WriteBoolean("clientRepeat", true);
                w.WriteBoolean("clientUnitIdCheck", true);
                w.WriteEndObject();
                w.WriteStartArray("serverFunctions");
                foreach (int function in FixtureDevice.Functions)
                {
                    w.WriteNumberValue(function);
                }

                w.WriteEndArray();
                w.WriteStartArray("clientOperations");
                foreach (string operation in Client.Operations)
                {
                    w.WriteStringValue(operation);
                }

                w.WriteEndArray();
            });
        }
    }
}
