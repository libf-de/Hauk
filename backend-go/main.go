// Command backend-go is a self-contained Hauk server. It implements the same
// API as the PHP backend, keeps all sessions in memory and serves the
// embedded web frontend.
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	configPath := flag.String("config", os.Getenv("HAUK_CONFIG"), "path to the YAML configuration file")
	listen := flag.String("listen", "", "address to listen on, overrides the listen option")
	flag.Parse()

	cfg, path, err := LoadConfig(*configPath, os.Environ())
	if err != nil {
		log.Fatalf("failed to load configuration: %v", err)
	}
	if path != "" {
		log.Printf("loaded configuration from %s", path)
	}
	if *listen != "" {
		cfg.Listen = *listen
	}
	if cfg.AuthMethod == AuthPassword && verifyPassword("", cfg.PasswordHash) {
		log.Printf("WARNING: the server password is empty; anyone can share their location using this server")
	}

	backend := NewBackend(cfg)
	server := &http.Server{
		Addr:              cfg.Listen,
		Handler:           backend.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Periodically remove expired sessions and shares from memory.
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				backend.Purge()
			case <-ctx.Done():
				return
			}
		}
	}()

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	log.Printf("Hauk %s listening on %s", BackendVersion, cfg.Listen)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
