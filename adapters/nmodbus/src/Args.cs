// SPDX-License-Identifier: MIT

using System;
using System.Collections.Generic;
using System.Globalization;

namespace ModbusInterop.NModbusAdapter
{
    /// <summary>
    /// "--name value" flags. A flag that is not in the allowed set is a usage
    /// error, never ignored.
    /// </summary>
    internal sealed class Flags
    {
        private readonly Dictionary<string, string> _values = new Dictionary<string, string>(StringComparer.Ordinal);

        public Flags(string[] args, int first, ICollection<string> allowed)
        {
            for (int i = first; i < args.Length; i++)
            {
                string arg = args[i];
                if (!arg.StartsWith("--", StringComparison.Ordinal) || arg.Length == 2)
                {
                    throw new UsageException("unexpected argument '" + arg + "'");
                }

                string name = arg.Substring(2);
                if (!allowed.Contains(name))
                {
                    throw new UsageException("unknown flag --" + name);
                }

                if (i + 1 >= args.Length)
                {
                    throw new UsageException("flag --" + name + " needs a value");
                }

                if (_values.ContainsKey(name))
                {
                    throw new UsageException("flag --" + name + " is given twice");
                }

                _values[name] = args[++i];
            }
        }

        public bool Has(string name)
        {
            return _values.ContainsKey(name);
        }

        public string Text(string name)
        {
            if (!_values.TryGetValue(name, out string? value))
            {
                throw new UsageException("flag --" + name + " is required");
            }

            return value;
        }

        public string Text(string name, string fallback)
        {
            return _values.TryGetValue(name, out string? value) ? value : fallback;
        }

        public int Number(string name, int min, int max)
        {
            return ParseNumber("--" + name, Text(name), min, max);
        }

        public int Number(string name, int min, int max, int fallback)
        {
            return Has(name) ? Number(name, min, max) : fallback;
        }

        /// <summary>Decimal, or hexadecimal with a 0x prefix.</summary>
        public static int ParseNumber(string what, string text, int min, int max)
        {
            long value = 0;
            bool parsed;
            if (text.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            {
                parsed = text.Length > 2 && text.Length <= 10 && IsHex(text, 2)
                    && long.TryParse(text.AsSpan(2), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out value);
            }
            else
            {
                parsed = long.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out value);
            }

            if (!parsed)
            {
                throw new UsageException(what + ": '" + text + "' is not a number");
            }

            if (value < min || value > max)
            {
                throw new UsageException(what + ": " + text + " is outside " + min + ".." + max);
            }

            return (int)value;
        }

        public static bool ParseBool(string what, string text)
        {
            switch (text)
            {
                case "true":
                case "1":
                case "on":
                    return true;
                case "false":
                case "0":
                case "off":
                    return false;
                default:
                    throw new UsageException(what + ": '" + text + "' is not a coil value (true/false, 1/0, on/off)");
            }
        }

        public static byte[] ParseHex(string what, string text)
        {
            if (text.Length % 2 != 0 || !IsHex(text, 0))
            {
                throw new UsageException(what + ": '" + text + "' is not an even number of hexadecimal digits");
            }

            return Convert.FromHexString(text);
        }

        private static bool IsHex(string text, int from)
        {
            for (int i = from; i < text.Length; i++)
            {
                if (!Uri.IsHexDigit(text[i]))
                {
                    return false;
                }
            }

            return true;
        }
    }
}
