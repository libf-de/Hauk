package main

import (
	"bufio"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base64"
	"log"
	"os"
	"strings"

	"github.com/GehirnInc/crypt"
	_ "github.com/GehirnInc/crypt/apr1_crypt"
	_ "github.com/GehirnInc/crypt/md5_crypt"
	_ "github.com/GehirnInc/crypt/sha256_crypt"
	_ "github.com/GehirnInc/crypt/sha512_crypt"
	"golang.org/x/crypto/bcrypt"
)

// verifyPassword checks a password against a hash. Supported are bcrypt
// ($2a$, $2b$, $2y$), MD5-crypt ($1$), Apache MD5 ($apr1$), SHA-crypt ($5$,
// $6$) and SHA-1 ({SHA}) hashes.
func verifyPassword(password, hash string) bool {
	switch {
	case strings.HasPrefix(hash, "$2"):
		return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
	case strings.HasPrefix(hash, "{SHA}"):
		sum := sha1.Sum([]byte(password))
		expected := base64.StdEncoding.EncodeToString(sum[:])
		return subtle.ConstantTimeCompare([]byte(expected), []byte(hash[len("{SHA}"):])) == 1
	case crypt.IsHashSupported(hash):
		return crypt.NewFromHash(hash).Verify(hash, []byte(password)) == nil
	default:
		log.Printf("unsupported password hash format: %.6s...", hash)
		return false
	}
}

// authResult is the outcome of an authentication attempt. If message is not
// empty, the request must be aborted with that message.
type authResult struct {
	ok      bool
	message string
}

// authenticate checks the credentials of a request against the configured
// authentication method.
func (b *Backend) authenticate(c *ctx) authResult {
	switch b.cfg.AuthMethod {
	case AuthPassword:
		password, ok := c.post("pwd")
		if !ok {
			return authResult{message: msgMissingData}
		}
		return authResult{ok: verifyPassword(password, b.cfg.PasswordHash)}

	case AuthHtpasswd:
		user, ok := c.post("usr")
		if !ok {
			return authResult{message: c.lang.T("username_required")}
		}
		password, ok := c.post("pwd")
		if !ok {
			return authResult{message: msgMissingData}
		}
		file, err := os.Open(b.cfg.HtpasswdPath)
		if err != nil {
			log.Printf("cannot open htpasswd file: %v", err)
			return authResult{message: c.lang.T("cannot_find_password_file")}
		}
		defer file.Close()
		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			name, hash, found := strings.Cut(strings.TrimSpace(scanner.Text()), ":")
			if found && name == user && verifyPassword(password, hash) {
				return authResult{ok: true}
			}
		}
		return authResult{}

	default:
		return authResult{}
	}
}
