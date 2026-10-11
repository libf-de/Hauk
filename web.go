// Package hauk embeds the web frontend so that it can be served by the Go
// backend in backend-go as part of a single binary.
package hauk

import "embed"

// Frontend contains the contents of the frontend directory.
//
//go:embed frontend
var Frontend embed.FS
