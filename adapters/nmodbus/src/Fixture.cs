// SPDX-License-Identifier: MIT

using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace ModbusInterop.NModbusAdapter
{
    /// <summary>The fixture is missing or invalid: exit 4.</summary>
    internal sealed class FixtureException : Exception
    {
        public FixtureException(string message)
            : base(message)
        {
        }
    }

    internal sealed class Block<T>
    {
        public Block(int address, T[] values)
        {
            Address = address;
            Values = values;
        }

        public int Address { get; }

        public T[] Values { get; }
    }

    /// <summary>
    /// A fixture, checked against the rules of
    /// fixtures/schema/modbus-fixture.schema.json and the cross-field rules of
    /// scripts/validate-fixtures.py.
    /// </summary>
    internal sealed class Fixture
    {
        private static readonly HashSet<string> RootKeys = new HashSet<string>(StringComparer.Ordinal)
        {
            "schemaVersion", "name", "description", "unitId", "coils", "discreteInputs", "holdingRegisters",
            "inputRegisters", "deviceIdentification", "reportServerId", "fileRecords", "fifoQueues",
        };

        private Fixture(string name, byte unitId)
        {
            Name = name;
            UnitId = unitId;
        }

        public string Name { get; }

        public byte UnitId { get; }

        public Block<bool>? Coils { get; private set; }

        public Block<bool>? DiscreteInputs { get; private set; }

        public Block<ushort>? HoldingRegisters { get; private set; }

        public Block<ushort>? InputRegisters { get; private set; }

        public static Fixture Load(string path)
        {
            byte[] bytes;
            try
            {
                bytes = File.ReadAllBytes(path);
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException || e is ArgumentException
                                      || e is NotSupportedException)
            {
                throw new FixtureException("cannot read " + path + ": " + e.Message);
            }

            JsonDocument document;
            try
            {
                document = JsonDocument.Parse(bytes);
            }
            catch (JsonException e)
            {
                throw new FixtureException(path + " is not JSON: " + e.Message);
            }

            using (document)
            {
                Fixture fixture = Parse(document.RootElement);
                string? directory = Path.GetFileName(Path.GetDirectoryName(Path.GetFullPath(path)));
                if (!string.Equals(directory, fixture.Name, StringComparison.Ordinal))
                {
                    throw new FixtureException("name '" + fixture.Name + "' is not the directory name '" + directory + "'");
                }

                return fixture;
            }
        }

        private static Fixture Parse(JsonElement root)
        {
            Object(root, "(root)", RootKeys);

            if (!root.TryGetProperty("schemaVersion", out JsonElement version)
                || version.ValueKind != JsonValueKind.String || version.GetString() != "1.0")
            {
                throw new FixtureException("schemaVersion must be \"1.0\"");
            }

            if (!root.TryGetProperty("name", out JsonElement nameElement) || nameElement.ValueKind != JsonValueKind.String
                || !IsName(nameElement.GetString()!))
            {
                throw new FixtureException("name must be a string of lower-case letters, digits and '-'");
            }

            if (root.TryGetProperty("description", out JsonElement description)
                && description.ValueKind != JsonValueKind.String)
            {
                throw new FixtureException("description must be a string");
            }

            if (!root.TryGetProperty("unitId", out JsonElement unit))
            {
                throw new FixtureException("unitId is required");
            }

            var fixture = new Fixture(nameElement.GetString()!, (byte)Integer(unit, "unitId", 1, 247));
            fixture.Coils = BitBlock(root, "coils");
            fixture.DiscreteInputs = BitBlock(root, "discreteInputs");
            fixture.HoldingRegisters = RegisterBlock(root, "holdingRegisters");
            fixture.InputRegisters = RegisterBlock(root, "inputRegisters");
            DeviceIdentification(root);
            ReportServerId(root);
            Tables(root, "fileRecords", "file", 1, "records", 1, 10000);
            Tables(root, "fifoQueues", "address", 0, "values", 0, 31);
            return fixture;
        }

        private static bool IsName(string name)
        {
            if (name.Length == 0 || name[0] == '-')
            {
                return false;
            }

            foreach (char c in name)
            {
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'))
                {
                    return false;
                }
            }

            return true;
        }

        public static bool IsFixtureName(string name)
        {
            return IsName(name);
        }

        private static void Object(JsonElement element, string where, ICollection<string> allowed)
        {
            if (element.ValueKind != JsonValueKind.Object)
            {
                throw new FixtureException(where + " must be an object");
            }

            foreach (JsonProperty property in element.EnumerateObject())
            {
                if (!allowed.Contains(property.Name))
                {
                    throw new FixtureException(where + ": unknown property '" + property.Name + "'");
                }
            }
        }

        private static int Integer(JsonElement element, string where, int min, int max)
        {
            if (element.ValueKind != JsonValueKind.Number)
            {
                throw new FixtureException(where + " must be an integer");
            }

            // JSON Schema calls 1.0 an integer too.
            double value = element.GetDouble();
            if (value != Math.Floor(value) || double.IsInfinity(value))
            {
                throw new FixtureException(where + " must be an integer");
            }

            if (value < min || value > max)
            {
                throw new FixtureException(where + " must be " + min + ".." + max);
            }

            return (int)value;
        }

        private static JsonElement? BlockElement(JsonElement root, string table, out int address, out int count)
        {
            address = 0;
            count = 0;
            if (!root.TryGetProperty(table, out JsonElement block))
            {
                return null;
            }

            Object(block, table, new[] { "address", "values" });
            if (!block.TryGetProperty("address", out JsonElement addressElement))
            {
                throw new FixtureException(table + ": address is required");
            }

            address = Integer(addressElement, table + ".address", 0, 65535);
            if (!block.TryGetProperty("values", out JsonElement values) || values.ValueKind != JsonValueKind.Array)
            {
                throw new FixtureException(table + ": values must be an array");
            }

            count = values.GetArrayLength();
            if (count < 1 || count > 65536)
            {
                throw new FixtureException(table + ": values must have 1..65536 items");
            }

            if (address + count - 1 > 65535)
            {
                throw new FixtureException(table + ": block ends at " + (address + count - 1) + ", past address 65535");
            }

            return values;
        }

        private static Block<bool>? BitBlock(JsonElement root, string table)
        {
            JsonElement? values = BlockElement(root, table, out int address, out int count);
            if (values == null)
            {
                return null;
            }

            var bits = new bool[count];
            int i = 0;
            foreach (JsonElement item in values.Value.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.True && item.ValueKind != JsonValueKind.False)
                {
                    throw new FixtureException(table + ".values[" + i + "] must be a boolean");
                }

                bits[i++] = item.ValueKind == JsonValueKind.True;
            }

            return new Block<bool>(address, bits);
        }

        private static Block<ushort>? RegisterBlock(JsonElement root, string table)
        {
            JsonElement? values = BlockElement(root, table, out int address, out int count);
            if (values == null)
            {
                return null;
            }

            var registers = new ushort[count];
            int i = 0;
            foreach (JsonElement item in values.Value.EnumerateArray())
            {
                registers[i] = (ushort)Integer(item, table + ".values[" + i + "]", 0, 65535);
                i++;
            }

            return new Block<ushort>(address, registers);
        }

        private static void DeviceIdentification(JsonElement root)
        {
            if (!root.TryGetProperty("deviceIdentification", out JsonElement identification))
            {
                return;
            }

            const string where = "deviceIdentification";
            Object(identification, where, new[] { "objects" });
            if (!identification.TryGetProperty("objects", out JsonElement objects) || objects.ValueKind != JsonValueKind.Array)
            {
                throw new FixtureException(where + ": objects must be an array");
            }

            if (objects.GetArrayLength() < 3)
            {
                throw new FixtureException(where + ": objects must have at least 3 items");
            }

            var ids = new List<int>();
            foreach (JsonElement item in objects.EnumerateArray())
            {
                Object(item, where + ".objects[]", new[] { "id", "value" });
                if (!item.TryGetProperty("id", out JsonElement idElement))
                {
                    throw new FixtureException(where + ": an object has no id");
                }

                int id = Integer(idElement, where + ": object id", 0, 255);
                if (!item.TryGetProperty("value", out JsonElement value) || value.ValueKind != JsonValueKind.String)
                {
                    throw new FixtureException(where + ": object " + id + " has no string value");
                }

                string text = value.GetString()!;
                if (text.Length < 1 || text.Length > 240)
                {
                    throw new FixtureException(where + ": object " + id + " must have 1..240 characters");
                }

                foreach (char c in text)
                {
                    if (c < 0x20 || c > 0x7e)
                    {
                        throw new FixtureException(where + ": object " + id + " is not printable ASCII");
                    }
                }

                if (ids.Count > 0 && id <= ids[ids.Count - 1])
                {
                    throw new FixtureException(where + ": object ids must be unique and ascending");
                }

                if (id >= 7 && id <= 127)
                {
                    throw new FixtureException(where + ": object id " + id + " is reserved");
                }

                ids.Add(id);
            }

            for (int basic = 0; basic <= 2; basic++)
            {
                if (!ids.Contains(basic))
                {
                    throw new FixtureException(where + ": basic object " + basic + " is missing");
                }
            }
        }

        /// <summary>
        /// reportServerId (FC17). Checked only: the server does not serve FC17
        /// (serverReportServerId is false), but a fixture with an invalid one
        /// is still refused.
        /// </summary>
        private static void ReportServerId(JsonElement root)
        {
            if (!root.TryGetProperty("reportServerId", out JsonElement element))
            {
                return;
            }

            const string where = "reportServerId";
            Object(element, where, new[] { "serverId", "runIndicator" });
            if (!element.TryGetProperty("serverId", out JsonElement serverId))
            {
                throw new FixtureException(where + ": serverId is required");
            }

            Integer(serverId, where + ".serverId", 0, 255);
            if (!element.TryGetProperty("runIndicator", out JsonElement run)
                || (run.ValueKind != JsonValueKind.True && run.ValueKind != JsonValueKind.False))
            {
                throw new FixtureException(where + ": runIndicator must be a boolean");
            }
        }

        /// <summary>
        /// fileRecords (FC20/FC21) and fifoQueues (FC24): a non-empty array of
        /// objects, each a unique key and an array of registers. Checked only:
        /// the server serves neither (serverFileRecord, serverFifoQueue false).
        /// </summary>
        private static void Tables(JsonElement root, string where, string key, int minKey, string list, int minItems, int maxItems)
        {
            if (!root.TryGetProperty(where, out JsonElement tables))
            {
                return;
            }

            if (tables.ValueKind != JsonValueKind.Array || tables.GetArrayLength() < 1)
            {
                throw new FixtureException(where + " must be an array with at least 1 item");
            }

            var keys = new HashSet<int>();
            int t = 0;
            foreach (JsonElement table in tables.EnumerateArray())
            {
                string item = where + "[" + t++ + "]";
                Object(table, item, new[] { key, list });
                if (!table.TryGetProperty(key, out JsonElement keyElement))
                {
                    throw new FixtureException(item + ": " + key + " is required");
                }

                int number = Integer(keyElement, item + "." + key, minKey, 65535);
                if (!keys.Add(number))
                {
                    throw new FixtureException(where + ": " + key + " " + number + " is not unique");
                }

                if (!table.TryGetProperty(list, out JsonElement values) || values.ValueKind != JsonValueKind.Array)
                {
                    throw new FixtureException(item + ": " + list + " must be an array");
                }

                int count = values.GetArrayLength();
                if (count < minItems || count > maxItems)
                {
                    throw new FixtureException(item + ": " + list + " must have " + minItems + ".." + maxItems + " items");
                }

                int i = 0;
                foreach (JsonElement value in values.EnumerateArray())
                {
                    Integer(value, item + "." + list + "[" + i++ + "]", 0, 65535);
                }
            }
        }
    }
}
