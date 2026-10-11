#!/usr/bin/env python3
"""Convert a config.php of the Hauk PHP backend to a config.yaml for the Go
backend.

Usage:
    convert-config.py /etc/hauk/config.php > /etc/hauk/config.yaml
    convert-config.py /etc/hauk/config.php -o /etc/hauk/config.yaml

The PHP file is parsed, not executed, so PHP does not need to be installed.
Literal values, arrays, Hauk's constants (e.g. LINK_16_HEX) and simple
arithmetic and string concatenation are supported. Options the Go backend does
not support (storage and LDAP settings) are omitted with a warning.
"""

import argparse
import json
import re
import sys

AUTH_METHODS = ["PASSWORD", "HTPASSWD", "LDAP"]
STORAGE_BACKENDS = ["MEMCACHED", "REDIS"]
LINK_STYLES = [
    "LINK_4_PLUS_4_UPPER_CASE",
    "LINK_4_PLUS_4_LOWER_CASE",
    "LINK_4_PLUS_4_MIXED_CASE",
    "LINK_UUID_V4",
    "LINK_16_HEX",
    "LINK_16_UPPER_CASE",
    "LINK_16_LOWER_CASE",
    "LINK_16_MIXED_CASE",
    "LINK_32_HEX",
    "LINK_32_UPPER_CASE",
    "LINK_32_LOWER_CASE",
    "LINK_32_MIXED_CASE",
]
VELOCITY_UNITS = {
    "KILOMETERS_PER_HOUR": "km/h",
    "MILES_PER_HOUR": "mph",
    "METERS_PER_SECOND": "m/s",
}

# Options supported by the Go backend and their types, in the order of
# config-sample.yaml.
OPTIONS = {
    "auth_method": "auth_method",
    "password_hash": "str",
    "htpasswd_path": "str",
    "allow_link_req": "bool",
    "reserved_links": "reserved_links",
    "reserve_whitelist": "bool",
    "link_style": "link_style",
    "map_tile_uri": "str",
    "map_attribution": "str",
    "default_zoom": "int",
    "max_zoom": "int",
    "max_duration": "int",
    "min_interval": "float",
    "offline_timeout": "int",
    "request_timeout": "int",
    "max_cached_pts": "int",
    "max_shown_pts": "int",
    "v_data_points": "int",
    "trail_color": "str",
    "velocity_unit": "velocity_unit",
    "public_url": "str",
}

# Options of the PHP backend that the Go backend does not need or support.
UNSUPPORTED = re.compile(r"^(storage_backend|memcached_.*|redis_.*|ldap_.*)$")


class ConvertError(Exception):
    pass


class Const:
    """A PHP constant, e.g. LINK_16_HEX."""

    def __init__(self, name):
        self.name = name

    def __repr__(self):
        return self.name


# --- Tokenizer ----------------------------------------------------------------

TOKEN = re.compile(
    r"""
    (?P<space>\s+)
  | (?P<comment>//[^\n]*|\#[^\n]*|/\*.*?\*/)
  | (?P<open><\?php)
  | (?P<close>\?>)
  | (?P<sq>'(?:[^'\\]|\\.)*')
  | (?P<dq>"(?:[^"\\]|\\.)*")
  | (?P<num>0[xX][0-9a-fA-F_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d+)?)
  | (?P<ident>[A-Za-z_\\][A-Za-z0-9_\\]*)
  | (?P<var>\$[A-Za-z_][A-Za-z0-9_]*)
  | (?P<punct>=>|[()\[\],;=.+\-*/])
  | (?P<other>.)
    """,
    re.VERBOSE | re.DOTALL,
)

DQ_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "v": "\v", "e": "\x1b", "f": "\f", "\\": "\\", "$": "$", '"': '"'}


def unquote_single(s):
    return re.sub(r"\\([\\'])", r"\1", s[1:-1])


def unquote_double(s, line):
    body = s[1:-1]
    if re.search(r"(?<!\\)(?:\\\\)*\$[A-Za-z_{]", body):
        raise ConvertError(f"line {line}: variables in strings are not supported: {s}")

    def replace(m):
        esc = m.group(1)
        if esc[0] in DQ_ESCAPES:
            return DQ_ESCAPES[esc[0]] + esc[1:]
        if esc[0] == "x":
            return chr(int(esc[1:], 16))
        if esc[0] == "u":
            return chr(int(esc[2:-1], 16))
        if esc[0] in "01234567":
            return chr(int(esc, 8))
        return m.group(0)

    return re.sub(r"\\(x[0-9a-fA-F]{1,2}|u\{[0-9a-fA-F]+\}|[0-7]{1,3}|.)", replace, body)


def tokenize(text):
    tokens = []
    pos = 0
    line = 1
    while pos < len(text):
        m = TOKEN.match(text, pos)
        kind = m.lastgroup
        value = m.group()
        if kind == "sq":
            tokens.append(("str", unquote_single(value), line))
        elif kind == "dq":
            tokens.append(("str", unquote_double(value, line), line))
        elif kind == "num":
            n = value.replace("_", "")
            if n.lower().startswith("0x"):
                tokens.append(("num", int(n, 16), line))
            elif re.fullmatch(r"\d+", n):
                tokens.append(("num", int(n), line))
            else:
                tokens.append(("num", float(n), line))
        elif kind in ("ident", "punct", "var", "other"):
            tokens.append((kind, value, line))
        line += value.count("\n")
        pos = m.end()
    return tokens


# --- Parser -------------------------------------------------------------------


class Parser:
    def __init__(self, tokens):
        self.tokens = tokens
        self.pos = 0

    def peek(self, offset=0):
        i = self.pos + offset
        return self.tokens[i] if i < len(self.tokens) else ("eof", None, self.tokens[-1][2] if self.tokens else 1)

    def next(self):
        tok = self.peek()
        self.pos += 1
        return tok

    def accept(self, kind, value=None):
        tok = self.peek()
        if tok[0] == kind and (value is None or tok[1] == value):
            self.pos += 1
            return True
        return False

    def expect(self, kind, value=None):
        tok = self.next()
        if tok[0] != kind or (value is not None and tok[1] != value):
            raise ConvertError(f"line {tok[2]}: expected {value or kind}, found {tok[1]!r}")
        return tok

    def find_config(self):
        """Positions the parser at the value assigned to CONFIG."""
        while self.peek()[0] != "eof":
            tok = self.next()
            if tok[0] == "ident" and tok[1].lower() == "const" and self.peek()[1] == "CONFIG":
                self.next()
                self.expect("punct", "=")
                return
            if tok[0] == "ident" and tok[1].lower() == "define" and self.peek()[1] == "(" and self.peek(1)[:2] == ("str", "CONFIG"):
                self.next()
                self.next()
                self.expect("punct", ",")
                return
        raise ConvertError("no CONFIG constant found; is this a Hauk config.php?")

    # Precedence from low to high: concatenation, addition, multiplication.
    def expression(self):
        value = self.additive()
        while self.accept("punct", "."):
            right = self.additive()
            value = php_string(value) + php_string(right)
        return value

    def additive(self):
        value = self.multiplicative()
        while self.peek()[:2] in (("punct", "+"), ("punct", "-")):
            op = self.next()
            right = self.multiplicative()
            value = arithmetic(op, value, right)
        return value

    def multiplicative(self):
        value = self.unary()
        while self.peek()[:2] in (("punct", "*"), ("punct", "/")):
            op = self.next()
            right = self.unary()
            value = arithmetic(op, value, right)
        return value

    def unary(self):
        if self.accept("punct", "-"):
            return arithmetic(("punct", "*", self.peek()[2]), -1, self.unary())
        if self.accept("punct", "+"):
            return self.unary()
        return self.primary()

    def primary(self):
        tok = self.next()
        kind, value, line = tok
        if kind in ("str", "num"):
            return value
        if kind == "punct" and value == "(":
            inner = self.expression()
            self.expect("punct", ")")
            return inner
        if kind == "punct" and value == "[":
            return self.array_items("]")
        if kind == "var":
            raise ConvertError(f"line {line}: variables are not supported: {value}")
        if kind == "ident":
            lower = value.lower()
            if lower == "array" and self.accept("punct", "("):
                return self.array_items(")")
            if lower == "true":
                return True
            if lower == "false":
                return False
            if lower == "null":
                return None
            return Const(value.lstrip("\\"))
        raise ConvertError(f"line {line}: unexpected {value!r}")

    def array_items(self, close):
        """Parses array items as a list of (key, value) pairs; key is None for
        items without an explicit key."""
        items = []
        while not self.accept("punct", close):
            value = self.expression()
            if self.accept("punct", "=>"):
                items.append((value, self.expression()))
            else:
                items.append((None, value))
            if not self.accept("punct", ","):
                self.expect("punct", close)
                break
        return items


def php_string(value):
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "1" if value else ""
    if value is None:
        return ""
    if isinstance(value, (int, float)):
        return str(value)
    raise ConvertError(f"cannot convert {value!r} to a string")


def arithmetic(op, left, right):
    if not all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in (left, right)):
        raise ConvertError(f"line {op[2]}: arithmetic is only supported on numbers")
    if op[1] == "+":
        return left + right
    if op[1] == "-":
        return left - right
    if op[1] == "*":
        return left * right
    result = left / right
    return int(result) if result == int(result) and isinstance(left, int) and isinstance(right, int) else result


# --- Conversion ---------------------------------------------------------------


def yaml_str(s):
    # A JSON string is a valid double-quoted YAML scalar.
    return json.dumps(s, ensure_ascii=False)


def enum_value(key, value, names):
    if isinstance(value, Const) and value.name in names:
        return value.name
    if isinstance(value, int) and not isinstance(value, bool) and 0 <= value < len(names):
        return names[value]
    raise ConvertError(f"{key}: invalid value {value!r}; expected one of {', '.join(names)}")


def convert_value(key, kind, value):
    """Returns the YAML representation of a supported option."""
    if kind == "str":
        if isinstance(value, Const):
            raise ConvertError(f"{key}: unknown constant {value.name}")
        return yaml_str(php_string(value))
    if kind == "bool":
        if isinstance(value, str):
            return "false" if value in ("", "0") else "true"
        if isinstance(value, (bool, int, float)) or value is None:
            return "true" if value else "false"
        raise ConvertError(f"{key}: expected a boolean, found {value!r}")
    if kind == "int":
        if isinstance(value, str) and re.fullmatch(r"\s*[+-]?\d+\s*", value):
            value = int(value)
        if isinstance(value, float) and value == int(value):
            value = int(value)
        if isinstance(value, int) and not isinstance(value, bool):
            return str(value)
        raise ConvertError(f"{key}: expected an integer, found {value!r}")
    if kind == "float":
        if isinstance(value, str):
            try:
                value = float(value)
            except ValueError:
                pass
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return repr(value)
        raise ConvertError(f"{key}: expected a number, found {value!r}")
    if kind == "auth_method":
        name = enum_value(key, value, AUTH_METHODS)
        if name == "LDAP":
            raise ConvertError("auth_method is LDAP, which the Go backend does not support; use PASSWORD or HTPASSWD instead")
        return name
    if kind == "link_style":
        return enum_value(key, value, LINK_STYLES)
    if kind == "velocity_unit":
        if isinstance(value, Const) and value.name in VELOCITY_UNITS:
            return value.name
        if isinstance(value, list):
            unit = dict((k, v) for k, v in value if k is not None).get("unit")
            for name, symbol in VELOCITY_UNITS.items():
                if unit == symbol:
                    return name
        raise ConvertError(f"{key}: invalid value {value!r}; expected one of {', '.join(VELOCITY_UNITS)}")
    if kind == "reserved_links":
        if not isinstance(value, list):
            raise ConvertError(f"{key}: expected an array, found {value!r}")
        if not value:
            return "{}"
        lines = []
        for link, users in value:
            if link is None or not isinstance(users, list):
                raise ConvertError(f"{key}: expected 'link' => ['user', ...] entries")
            names = [php_string(user) for _, user in users]
            lines.append(f"  {yaml_str(php_string(link))}: [{', '.join(yaml_str(n) for n in names)}]")
        return "\n" + "\n".join(lines)
    raise AssertionError(kind)


def convert(text, source):
    parser = Parser(tokenize(text))
    parser.find_config()
    config = parser.expression()
    if not isinstance(config, list):
        raise ConvertError("CONFIG is not an array")

    warnings = []
    values = {}
    unsupported = []
    for key, value in config:
        if not isinstance(key, str):
            warnings.append(f"ignoring CONFIG entry without a string key: {value!r}")
        elif key in OPTIONS:
            values[key] = convert_value(key, OPTIONS[key], value)
        elif UNSUPPORTED.match(key):
            unsupported.append(key)
        else:
            warnings.append(f"omitting unknown option {key}")
    if unsupported:
        warnings.append("omitting storage and LDAP options, which the Go backend does not support: " + ", ".join(unsupported))

    lines = [
        f"# Converted from {source} by convert-config.py.",
        "# See config-sample.yaml for a description of all options.",
        "",
    ]
    for key in OPTIONS:
        if key in values:
            separator = "" if values[key].startswith("\n") else " "
            lines.append(f"{key}:{separator}{values[key]}")
    return "\n".join(lines) + "\n", warnings


def main():
    parser = argparse.ArgumentParser(description="Convert a Hauk config.php to a config.yaml for the Go backend.")
    parser.add_argument("input", help="path to config.php")
    parser.add_argument("-o", "--output", help="path to write config.yaml to (default: standard output)")
    args = parser.parse_args()

    try:
        with open(args.input, encoding="utf-8") as f:
            text = f.read()
        output, warnings = convert(text, args.input)
    except (OSError, ConvertError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output)
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
