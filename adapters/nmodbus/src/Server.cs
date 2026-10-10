// SPDX-License-Identifier: MIT

using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using NModbus;

namespace ModbusInterop.NModbusAdapter
{
    /// <summary>The "server" command: an NModbus TCP slave network that serves one fixture.</summary>
    internal static class Server
    {
        private static readonly string[] Allowed = { "fixture", "bind-address", "port", "ready-file" };

        public static int Run(string[] args)
        {
            var flags = new Flags(args, 1, Allowed);
            string fixturePath = flags.Text("fixture", "/fixtures/baseline/fixture.json");
            string bindText = flags.Text("bind-address", "0.0.0.0");
            int port = flags.Number("port", 1, 65535, 1502);
            string readyFile = flags.Text("ready-file", "/run/modbus-interop/ready");
            if (!IPAddress.TryParse(bindText, out IPAddress? bindAddress))
            {
                throw new UsageException("--bind-address: '" + bindText + "' is not an IP address");
            }

            Fixture fixture;
            try
            {
                fixture = Fixture.Load(fixturePath);
            }
            catch (FixtureException e)
            {
                Output.Diagnostic("invalid fixture: " + e.Message);
                return 4;
            }

            // NModbus starts the listener in ListenAsync; starting it here first
            // (a second Start is a no-op) reports a port that is taken before
            // the device is declared ready.
            var endPoint = new IPEndPoint(bindAddress, port);
            var listener = new TcpListener(endPoint);
            try
            {
                listener.Start();
            }
            catch (SocketException e)
            {
                Output.Diagnostic("cannot listen on " + endPoint + ": " + e.Message);
                return 1;
            }

            ModbusFactory factory = FixtureDevice.CreateFactory(new StderrLogger());
            IModbusTcpSlaveNetwork network = factory.CreateSlaveNetwork(listener);
            FixtureDevice.Populate(network, factory, fixture);

            using var stop = new ManualResetEventSlim(false);
            using var cancel = new CancellationTokenSource();
            Action<PosixSignalContext> onSignal = context =>
            {
                context.Cancel = true;
                stop.Set();
            };
            using PosixSignalRegistration term = PosixSignalRegistration.Create(PosixSignal.SIGTERM, onSignal);
            using PosixSignalRegistration interrupt = PosixSignalRegistration.Create(PosixSignal.SIGINT, onSignal);

            Task listening = network.ListenAsync(cancel.Token);
            listening.ContinueWith(_ => stop.Set(), TaskScheduler.Default);

            try
            {
                string? directory = Path.GetDirectoryName(Path.GetFullPath(readyFile));
                if (!string.IsNullOrEmpty(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                File.WriteAllText(readyFile, "ready\n");
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
            {
                Output.Diagnostic("cannot write the readiness file " + readyFile + ": " + e.Message);
                network.Dispose();
                return 1;
            }

            Output.Line(writer =>
            {
                writer.WriteString("event", "ready");
                writer.WriteString("adapter", Program.Adapter);
                writer.WriteString("address", endPoint.ToString());
                writer.WriteString("fixture", fixture.Name);
                writer.WriteNumber("unitId", fixture.UnitId);
            });

            stop.Wait();
            bool failed = listening.IsCompleted;

            try
            {
                File.Delete(readyFile);
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
            {
                Output.Diagnostic("cannot remove the readiness file: " + e.Message);
            }

            cancel.Cancel();
            network.Dispose();
            try
            {
                listening.Wait(TimeSpan.FromSeconds(2));
            }
            catch (AggregateException e)
            {
                // Stopping the listener ends the pending accept with an error.
                if (failed)
                {
                    Output.Diagnostic("the listener failed: " + e.InnerException?.Message);
                }
            }

            if (failed)
            {
                Output.Diagnostic("the listener stopped unexpectedly");
                return 1;
            }

            Output.Line(writer => writer.WriteString("event", "stopped"));
            return 0;
        }
    }
}
