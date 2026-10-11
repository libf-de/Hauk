package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"reflect"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// Authentication methods.
type AuthMethod int

const (
	AuthPassword AuthMethod = iota
	AuthHtpasswd
)

// Share link styles. The numeric values match the constants of the PHP
// backend so that existing configurations can be converted as-is.
type LinkStyle int

const (
	Link4Plus4UpperCase LinkStyle = iota
	Link4Plus4LowerCase
	Link4Plus4MixedCase
	LinkUUIDv4
	Link16Hex
	Link16UpperCase
	Link16LowerCase
	Link16MixedCase
	Link32Hex
	Link32UpperCase
	Link32LowerCase
	Link32MixedCase
)

var linkStyleNames = []string{
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
}

// VelocityUnit is the unit in which velocities are displayed on the map. It is
// passed to the frontend as-is.
type VelocityUnit struct {
	MpsMultiplier float64 `json:"mpsMultiplier"`
	Unit          string  `json:"unit"`
}

var (
	KilometersPerHour = VelocityUnit{MpsMultiplier: 3.6, Unit: "km/h"}
	MilesPerHour      = VelocityUnit{MpsMultiplier: 3.6 * 0.6213712, Unit: "mph"}
	MetersPerSecond   = VelocityUnit{MpsMultiplier: 1, Unit: "m/s"}
)

// Config contains all configuration options. The option names are the same as
// in config.php of the PHP backend. Options related to storage backends and
// LDAP are not supported.
type Config struct {
	Listen string `yaml:"listen"`

	AuthMethod   AuthMethod `yaml:"auth_method"`
	PasswordHash string     `yaml:"password_hash"`
	HtpasswdPath string     `yaml:"htpasswd_path"`

	AllowLinkReq     bool                `yaml:"allow_link_req"`
	ReservedLinks    map[string][]string `yaml:"reserved_links"`
	ReserveWhitelist bool                `yaml:"reserve_whitelist"`
	LinkStyle        LinkStyle           `yaml:"link_style"`

	MapTileURI     string       `yaml:"map_tile_uri"`
	MapAttribution string       `yaml:"map_attribution"`
	DefaultZoom    int          `yaml:"default_zoom"`
	MaxZoom        int          `yaml:"max_zoom"`
	MaxDuration    int64        `yaml:"max_duration"`
	MinInterval    float64      `yaml:"min_interval"`
	OfflineTimeout int          `yaml:"offline_timeout"`
	RequestTimeout int          `yaml:"request_timeout"`
	MaxCachedPts   int          `yaml:"max_cached_pts"`
	MaxShownPts    int          `yaml:"max_shown_pts"`
	VDataPoints    int          `yaml:"v_data_points"`
	TrailColor     string       `yaml:"trail_color"`
	VelocityUnit   VelocityUnit `yaml:"velocity_unit"`
	PublicURL      string       `yaml:"public_url"`
}

// DefaultPasswordHash is the bcrypt hash of an empty password, which is the
// default of the PHP backend.
const DefaultPasswordHash = "$2y$10$4ZP1iY8A3dZygXoPgsXYV.S3gHzBbiT9nSfONjhWrvMxVPkcFq1Ka"

// DefaultConfig returns the configuration used for options that are not set.
func DefaultConfig() Config {
	return Config{
		Listen:           ":8080",
		AuthMethod:       AuthPassword,
		PasswordHash:     DefaultPasswordHash,
		HtpasswdPath:     "/etc/hauk/users.htpasswd",
		AllowLinkReq:     true,
		ReservedLinks:    map[string][]string{},
		ReserveWhitelist: false,
		LinkStyle:        Link4Plus4UpperCase,
		MapTileURI:       "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
		MapAttribution:   `Map data &copy; <a href="https://www.openstreetmap.org/">OpenStreetMap</a> contributors, <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>`,
		DefaultZoom:      14,
		MaxZoom:          19,
		MaxDuration:      86400,
		MinInterval:      1,
		OfflineTimeout:   30,
		RequestTimeout:   10,
		MaxCachedPts:     3,
		MaxShownPts:      100,
		VDataPoints:      2,
		TrailColor:       "#d80037",
		VelocityUnit:     KilometersPerHour,
		PublicURL:        "https://example.com/",
	}
}

// ConfigPaths are searched in order for a configuration file if none is given.
var ConfigPaths = []string{
	"/etc/hauk/config.yaml",
	"/usr/local/etc/hauk/config.yaml",
	"config.yaml",
}

// EnvPrefix is the prefix of environment variables overriding config options,
// e.g. HAUK_PUBLIC_URL overrides public_url.
const EnvPrefix = "HAUK_"

// LoadConfig loads the configuration from the given YAML file, or from the
// first file in ConfigPaths that exists if path is empty, and then applies
// overrides from environment variables. It fails if neither a configuration
// file nor any HAUK_* environment variable is found, to prevent accidentally
// running an instance with the default empty password.
func LoadConfig(path string, environ []string) (Config, string, error) {
	cfg := DefaultConfig()

	if path == "" {
		for _, candidate := range ConfigPaths {
			if _, err := os.Stat(candidate); err == nil {
				path = candidate
				break
			}
		}
	}
	if path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			return cfg, path, err
		}
		dec := yaml.NewDecoder(bytes.NewReader(data))
		dec.KnownFields(true)
		if err := dec.Decode(&cfg); err != nil && !errors.Is(err, io.EOF) {
			return cfg, path, fmt.Errorf("%s: %w", path, err)
		}
	}

	applied, err := applyEnv(&cfg, environ)
	if err != nil {
		return cfg, path, err
	}
	if path == "" && !applied {
		return cfg, path, fmt.Errorf("no configuration found; create one of %s or set %s* environment variables", strings.Join(ConfigPaths, ", "), EnvPrefix)
	}
	if cfg.ReservedLinks == nil {
		cfg.ReservedLinks = map[string][]string{}
	}
	return cfg, path, nil
}

// applyEnv overrides config options with HAUK_* environment variables. String
// options are taken verbatim; all other options are parsed as YAML, e.g.
// HAUK_RESERVED_LINKS='{WheresAlice: [alice]}'.
func applyEnv(cfg *Config, environ []string) (bool, error) {
	env := map[string]string{}
	for _, kv := range environ {
		if k, v, ok := strings.Cut(kv, "="); ok && strings.HasPrefix(k, EnvPrefix) {
			env[k] = v
		}
	}

	applied := false
	v := reflect.ValueOf(cfg).Elem()
	t := v.Type()
	for i := 0; i < t.NumField(); i++ {
		key := t.Field(i).Tag.Get("yaml")
		value, ok := env[EnvPrefix+strings.ToUpper(key)]
		if !ok {
			continue
		}
		applied = true
		field := v.Field(i)
		if field.Kind() == reflect.String {
			field.SetString(value)
			continue
		}
		if err := yaml.Unmarshal([]byte(value), field.Addr().Interface()); err != nil {
			return applied, fmt.Errorf("%s%s: %w", EnvPrefix, strings.ToUpper(key), err)
		}
	}
	return applied, nil
}

func (m *AuthMethod) UnmarshalYAML(node *yaml.Node) error {
	switch strings.ToUpper(node.Value) {
	case "PASSWORD", "0":
		*m = AuthPassword
	case "HTPASSWD", "1":
		*m = AuthHtpasswd
	case "LDAP", "2":
		return errors.New("auth_method LDAP is not supported by the Go backend")
	default:
		return fmt.Errorf("invalid auth_method %q; valid values are PASSWORD and HTPASSWD", node.Value)
	}
	return nil
}

func (s *LinkStyle) UnmarshalYAML(node *yaml.Node) error {
	if n, err := strconv.Atoi(node.Value); err == nil && n >= 0 && n < len(linkStyleNames) {
		*s = LinkStyle(n)
		return nil
	}
	name := strings.ToUpper(node.Value)
	if !strings.HasPrefix(name, "LINK_") {
		name = "LINK_" + name
	}
	for i, candidate := range linkStyleNames {
		if candidate == name {
			*s = LinkStyle(i)
			return nil
		}
	}
	return fmt.Errorf("invalid link_style %q; valid values are %s", node.Value, strings.Join(linkStyleNames, ", "))
}

func (u *VelocityUnit) UnmarshalYAML(node *yaml.Node) error {
	switch strings.ToUpper(node.Value) {
	case "KILOMETERS_PER_HOUR", "KM/H", "KMH":
		*u = KilometersPerHour
	case "MILES_PER_HOUR", "MPH":
		*u = MilesPerHour
	case "METERS_PER_SECOND", "M/S":
		*u = MetersPerSecond
	default:
		return fmt.Errorf("invalid velocity_unit %q; valid values are KILOMETERS_PER_HOUR, MILES_PER_HOUR and METERS_PER_SECOND", node.Value)
	}
	return nil
}
