package main

import "testing"

func TestSampleConfigLoads(t *testing.T) {
	cfg, _, err := LoadConfig("config-sample.yaml", nil)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen != ":8080" || cfg.ReservedLinks["TheRealBob"][1] != "charlie" || cfg.PasswordHash != DefaultPasswordHash {
		t.Errorf("unexpected sample config %+v", cfg)
	}
}
