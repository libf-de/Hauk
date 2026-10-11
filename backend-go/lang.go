package main

import (
	"embed"
	"encoding/json"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

//go:embed lang/*.json
var langFiles embed.FS

// translations maps language codes to translated messages.
var translations = loadTranslations()

// languages lists the codes of all available languages, e.g. "nb_NO".
var languages = func() []string {
	codes := make([]string, 0, len(translations))
	for code := range translations {
		codes = append(codes, code)
	}
	sort.Strings(codes)
	return codes
}()

func loadTranslations() map[string]map[string]string {
	entries, err := langFiles.ReadDir("lang")
	if err != nil {
		panic(err)
	}
	out := map[string]map[string]string{}
	for _, entry := range entries {
		data, err := langFiles.ReadFile("lang/" + entry.Name())
		if err != nil {
			panic(err)
		}
		texts := map[string]string{}
		if err := json.Unmarshal(data, &texts); err != nil {
			panic(entry.Name() + ": " + err.Error())
		}
		out[strings.TrimSuffix(entry.Name(), ".json")] = texts
	}
	return out
}

// Lang is the language used for messages in a response.
type Lang string

// T returns the message with the given key, falling back to English.
func (l Lang) T(key string) string {
	if text, ok := translations[string(l)][key]; ok {
		return text
	}
	return translations["en"][key]
}

var acceptLanguage = regexp.MustCompile(`(?i)(([a-z]{1,8})(_([a-z]{1,8}))?)(\s*;\s*q\s*=\s*([01](\.\d{0,3})?))?\s*(,|$)`)

// negotiateLang picks the best available language for an Accept-Language
// header. Languages with a matching country code are preferred over those
// that only match the language.
func negotiateLang(header string) Lang {
	header = strings.ReplaceAll(header, "-", "_")
	best := "en"
	bestQ := 0.0
	for _, m := range acceptLanguage.FindAllStringSubmatch(header, -1) {
		code, short := m[1], m[2]
		q := 1.0
		if m[6] != "" {
			q, _ = strconv.ParseFloat(m[6], 64)
		}
		if q > bestQ && hasLanguage(code) {
			best, bestQ = code, q
		} else if q*0.9 > bestQ {
			for _, candidate := range languages {
				if candidate[:2] == short {
					best, bestQ = candidate, q*0.9
					break
				}
			}
		}
	}
	return Lang(best)
}

func hasLanguage(code string) bool {
	_, ok := translations[code]
	return ok
}
