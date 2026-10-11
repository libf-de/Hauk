package main

import (
	"bytes"
	"encoding/json"
	"math"
	"regexp"
	"strconv"
	"strings"
)

// keyValue is an entry of an orderedMap.
type keyValue struct {
	Key   string
	Value any
}

// orderedMap is a JSON object that preserves the order of its keys, like PHP
// arrays do. Used for maps keyed by nickname so that participants are listed
// in the order they joined.
type orderedMap []keyValue

func (m orderedMap) MarshalJSON() ([]byte, error) {
	var buf bytes.Buffer
	buf.WriteByte('{')
	for i, kv := range m {
		if i > 0 {
			buf.WriteByte(',')
		}
		key, err := json.Marshal(kv.Key)
		if err != nil {
			return nil, err
		}
		value, err := json.Marshal(kv.Value)
		if err != nil {
			return nil, err
		}
		buf.Write(key)
		buf.WriteByte(':')
		buf.Write(value)
	}
	buf.WriteByte('}')
	return buf.Bytes(), nil
}

var (
	floatPrefix = regexp.MustCompile(`^[ \t\n\r\v\f]*[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?`)
	intPrefix   = regexp.MustCompile(`^[ \t\n\r\v\f]*[+-]?[0-9]+`)
	numeric     = regexp.MustCompile(`^[ \t\n\r\v\f]*[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?[ \t\n\r\v\f]*$`)
)

// phpFloatval converts a string to a float like PHP's floatval(): the leading
// numeric part is parsed and the rest ignored; non-numeric strings yield 0.
func phpFloatval(s string) float64 {
	f, err := strconv.ParseFloat(strings.TrimSpace(floatPrefix.FindString(s)), 64)
	if err != nil || math.IsInf(f, 0) {
		return 0
	}
	return f
}

// phpIntval converts a string to an integer like PHP's intval().
func phpIntval(s string) int64 {
	if isNumeric(s) {
		f := phpFloatval(s)
		if f >= math.MaxInt64 {
			return math.MaxInt64
		} else if f <= math.MinInt64 {
			return math.MinInt64
		}
		return int64(f)
	}
	n, err := strconv.ParseInt(strings.TrimSpace(intPrefix.FindString(s)), 10, 64)
	if err != nil {
		return 0
	}
	return n
}

// isNumeric reports whether the string is a decimal number, like PHP's
// is_numeric() (hexadecimal and special values are not accepted).
func isNumeric(s string) bool {
	return numeric.MatchString(s)
}
