package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"

	hauk "github.com/bilde2910/Hauk"
)

// BackendVersion is reported to clients in the X-Hauk-Version header. Clients
// use it to determine which features the backend supports.
const BackendVersion = "1.6.3"

// Share modes for create.php. Correspond to the constants in the Android app.
const (
	shareModeCreateAlone = 0
	shareModeCreateGroup = 1
	shareModeJoinGroup   = 2
)

const (
	msgMissingData = "Missing data!\n"

	navRouteMaxLength = 200000

	// Maximum size of a request body. Requests carrying a navigation route
	// can be a few hundred kilobytes large.
	maxRequestBodySize = 2 << 20
)

var (
	customLinkPattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	// Encoded polylines only consist of characters in the ASCII range 63-126.
	polylinePattern = regexp.MustCompile(`^[\x3F-\x7E]*$`)
)

// ctx holds the state of a single API request.
type ctx struct {
	w    http.ResponseWriter
	r    *http.Request
	lang Lang
}

// post returns a form value from the request body, like $_POST in PHP.
func (c *ctx) post(key string) (string, bool) {
	values, ok := c.r.PostForm[key]
	if !ok || len(values) == 0 {
		return "", false
	}
	return values[0], true
}

// requirePOST checks that all given fields are present in the request body. If
// not, it writes an error and returns false.
func (c *ctx) requirePOST(keys ...string) bool {
	for _, key := range keys {
		if _, ok := c.post(key); !ok {
			c.write(msgMissingData)
			return false
		}
	}
	return true
}

// write writes a plain text response.
func (c *ctx) write(text string) {
	_, _ = c.w.Write([]byte(text))
}

// writeLines writes each line followed by a newline.
func (c *ctx) writeLines(lines ...string) {
	c.write(strings.Join(lines, "\n") + "\n")
}

// Handler returns the HTTP handler serving the API, the frontend
// configuration and the embedded frontend.
func (b *Backend) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/create.php", b.api(b.handleCreate))
	mux.HandleFunc("/api/post.php", b.api(b.handlePost))
	mux.HandleFunc("/api/fetch.php", b.api(b.handleFetch))
	mux.HandleFunc("/api/stop.php", b.api(b.handleStop))
	mux.HandleFunc("/api/adopt.php", b.api(b.handleAdopt))
	mux.HandleFunc("/api/new-link.php", b.api(b.handleNewLink))
	mux.HandleFunc("/dynamic.js.php", b.handleDynamicJS)

	frontend, err := fs.Sub(hauk.Frontend, "frontend")
	if err != nil {
		panic(err)
	}
	mux.Handle("/", frontendHandler(frontend))
	return mux
}

// frontendHandler serves the frontend files. Paths that consist of a share ID,
// e.g. /1234-ABCD, are redirected to the view link /?1234-ABCD.
func frontendHandler(frontend fs.FS) http.Handler {
	files := http.FileServerFS(frontend)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimPrefix(r.URL.Path, "/")
		if (r.Method == http.MethodGet || r.Method == http.MethodHead) && customLinkPattern.MatchString(id) {
			if _, err := fs.Stat(frontend, id); errors.Is(err, fs.ErrNotExist) {
				// The Location is relative so that the redirect also works
				// when Hauk is served from a subdirectory by a reverse proxy.
				w.Header().Set("Location", "./?"+id)
				w.WriteHeader(http.StatusFound)
				return
			}
		}
		files.ServeHTTP(w, r)
	})
}

// api wraps an API handler with request parsing and common headers.
func (b *Backend) api(handler func(c *ctx)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodySize)
		_ = r.ParseForm()
		w.Header().Set("X-Hauk-Version", BackendVersion)
		w.Header().Set("Content-Type", "text/html; charset=UTF-8")
		handler(&ctx{w: w, r: r, lang: negotiateLang(r.Header.Get("Accept-Language"))})
	}
}

// handleCreate creates a session and a share or joins a group share.
func (b *Backend) handleCreate(c *ctx) {
	if !c.requirePOST("dur", "int") {
		return
	}

	// Verify that the client is authorized to connect. This may read the
	// htpasswd file, so it is done before locking.
	auth := b.authenticate(c)
	if auth.message != "" {
		c.write(auth.message)
		return
	} else if !auth.ok {
		c.write(c.lang.T("incorrect_password") + "\n")
		return
	}

	dur, _ := c.post("dur")
	intv, _ := c.post("int")
	d := phpIntval(dur)
	i := phpFloatval(intv)
	mode := int64(shareModeCreateAlone)
	if v, ok := c.post("mod"); ok {
		mode = phpIntval(v)
	}
	if d > b.cfg.MaxDuration {
		c.write(c.lang.T("share_too_long") + "\n")
		return
	}
	if i > float64(b.cfg.MaxDuration) {
		c.write(c.lang.T("interval_too_long") + "\n")
		return
	}
	if i < b.cfg.MinInterval {
		c.write(c.lang.T("interval_too_short") + "\n")
		return
	}

	var adoptable, encrypted int64
	if v, ok := c.post("ado"); ok {
		adoptable = phpIntval(v)
	}
	if v, ok := c.post("e2e"); ok {
		encrypted = phpIntval(v)
	}

	switch mode {
	case shareModeCreateGroup:
		if encrypted > 0 {
			c.write(c.lang.T("group_e2e_unsupported") + "\n")
			return
		}
		if !c.requirePOST("nic") {
			return
		}
	case shareModeJoinGroup:
		if encrypted > 0 {
			c.write(c.lang.T("group_e2e_unsupported") + "\n")
			return
		}
		if !c.requirePOST("nic", "pin") {
			return
		}
	}
	if encrypted != 0 && !c.requirePOST("salt") {
		return
	}

	// Ignore invalid custom link requests.
	custom, hasCustom := c.post("lid")
	hasCustom = hasCustom && customLinkPattern.MatchString(custom)
	var user *string
	if v, ok := c.post("usr"); ok {
		user = &v
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	host := b.NewSession(b.unix()+d, i)
	b.SaveSession(host)

	switch mode {
	case shareModeCreateAlone:
		share := b.NewSoloShare()
		if hasCustom {
			b.RequestCustomLink(share, custom, user)
		}
		share.Adoptable = adoptable
		share.Host = host.ID
		share.Expire = host.Expire
		b.SaveShare(share)

		host.AddTarget(share)
		host.Encrypted = encrypted
		if salt, ok := c.post("salt"); ok {
			host.Salt = &salt
		}
		b.SaveSession(host)
		c.writeLines("OK", host.ID, b.ViewLink(share), share.ID)

	case shareModeCreateGroup:
		nick, _ := c.post("nic")
		share := b.NewGroupShare()
		if hasCustom {
			b.RequestCustomLink(share, custom, user)
		}
		b.AddHost(share, nick, host)
		share.Expire = host.Expire
		b.SaveShare(share)

		host.AddTarget(share)
		b.SaveSession(host)
		c.writeLines("OK", host.ID, b.ViewLink(share), strconv.Itoa(share.PIN), share.ID)

	case shareModeJoinGroup:
		nick, _ := c.post("nic")
		pin, _ := c.post("pin")
		share := b.GetShareByPIN(pin)
		if share == nil {
			c.write(c.lang.T("group_pin_invalid") + "\n")
			return
		}
		b.AddHost(share, nick, host)
		b.SaveShare(share)

		host.AddTarget(share)
		b.SaveSession(host)
		c.writeLines("OK", host.ID, b.ViewLink(share), share.ID)

	default:
		c.writeLines(c.lang.T("share_mode_unsupported"))
	}
}

// handlePost stores a location update, and optionally navigation data.
func (b *Backend) handlePost(c *ctx) {
	if !c.requirePOST("lat", "lon", "time", "sid") {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	sid, _ := c.post("sid")
	session := b.GetSession(sid)
	if session == nil {
		c.write(c.lang.T("session_expired") + "\n")
		return
	}

	latStr, _ := c.post("lat")
	lonStr, _ := c.post("lon")
	timeStr, _ := c.post("time")
	route, hasRoute := c.post("nrt")
	hasRoute = hasRoute && len(route) <= navRouteMaxLength
	eta, hasETA := c.post("neta")
	dist, hasDist := c.post("ndst")

	if !session.IsEncrypted() {
		lat := phpFloatval(latStr)
		lon := phpFloatval(lonStr)
		if lat < -90 || lat > 90 || lon < -180 || lon > 180 {
			c.write(c.lang.T("location_invalid") + "\n")
			return
		}

		// Not all devices report speed and accuracy.
		var speed, accuracy any
		if v, ok := c.post("spd"); ok {
			speed = phpFloatval(v)
		}
		if v, ok := c.post("acc"); ok {
			accuracy = phpFloatval(v)
		}
		provider := 0
		if v, ok := c.post("prv"); ok && isNumeric(v) && phpFloatval(v) == 1 {
			provider = 1
		}
		session.AddPoint([]any{lat, lon, phpFloatval(timeStr), provider, accuracy, speed}, b.cfg.MaxCachedPts)

		// Navigation data is optional. Invalid navigation data is ignored
		// rather than rejected so that it never prevents location updates
		// from being stored.
		if hasRoute && polylinePattern.MatchString(route) {
			b.SetNavRoute(session, route, nil)
		}
		if hasETA && hasDist && isNumeric(eta) && isNumeric(dist) {
			b.SetNavProgress(session, phpFloatval(eta), max(0, phpFloatval(dist)), nil)
		}
	} else {
		// Input validation cannot be performed for end-to-end encrypted data.
		if !c.requirePOST("iv") {
			return
		}
		iv, _ := c.post("iv")
		optional := func(key string) any {
			if v, ok := c.post(key); ok {
				return v
			}
			return nil
		}
		session.AddPoint([]any{iv, latStr, lonStr, timeStr, optional("prv"), optional("acc"), optional("spd")}, b.cfg.MaxCachedPts)

		// Optional navigation data, encrypted with the same IV.
		if hasRoute {
			b.SetNavRoute(session, route, &iv)
		}
		if hasETA && hasDist {
			b.SetNavProgress(session, eta, dist, &iv)
		}
	}

	// Clients send this flag when navigation has ended.
	if _, ok := c.post("nend"); ok {
		session.ClearNav()
	}

	b.SaveSession(session)

	if session.Expire <= b.unix() {
		c.write(c.lang.T("session_expired") + "\n")
		return
	}
	c.writeLines("OK", b.cfg.PublicURL+"?%s", strings.Join(session.Targets, ","))
}

// handleFetch returns the location data of a share to the frontend.
func (b *Backend) handleFetch(c *ctx) {
	query := c.r.URL.Query()
	if !query.Has("id") {
		c.write(c.lang.T("session_invalid") + "\n")
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	share := b.GetShare(query.Get("id"))
	if share == nil {
		c.w.WriteHeader(http.StatusNotFound)
		c.write(c.lang.T("session_invalid") + "\n")
		return
	}

	var since *float64
	if query.Has("since") {
		if f, err := strconv.ParseFloat(query.Get("since"), 64); err == nil {
			since = &f
		}
	}

	// IDs of navigation routes the client already has, so that they aren't
	// sent again on every update.
	var knownRouteIDs []string
	if query.Has("rids") {
		knownRouteIDs = strings.Split(query.Get("rids"), ",")
	}

	var data any
	switch share.Type {
	case ShareTypeAlone:
		session := b.GetSession(share.Host)
		if session == nil {
			c.w.WriteHeader(http.StatusNotFound)
			c.write(c.lang.T("session_invalid") + "\n")
			return
		}
		var nav any
		if n := session.GetNav(knownRouteIDs); n != nil {
			nav = n
		}
		data = map[string]any{
			"type":       share.Type,
			"expire":     share.Expire,
			"serverTime": float64(b.now().UnixMicro()) / 1e6,
			"interval":   session.Interval,
			"points":     session.GetPoints(since),
			"encrypted":  session.IsEncrypted(),
			"salt":       session.Salt,
			"nav":        nav,
		}
	case ShareTypeGroup:
		data = map[string]any{
			"type":       share.Type,
			"expire":     share.Expire,
			"serverTime": float64(b.now().UnixMicro()) / 1e6,
			"interval":   b.AutoInterval(share),
			"points":     b.AllPoints(share, since),
			"nav":        b.AllNav(share, knownRouteIDs),
		}
	}

	out, err := json.Marshal(data)
	if err != nil {
		http.Error(c.w, err.Error(), http.StatusInternalServerError)
		return
	}
	c.w.Header().Set("Content-Type", "text/json")
	c.write(string(out) + "\n")
}

// handleStop ends a session, or removes it from a single share.
func (b *Backend) handleStop(c *ctx) {
	if !c.requirePOST("sid") {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	sid, _ := c.post("sid")
	session := b.GetSession(sid)
	if lid, ok := c.post("lid"); ok {
		// Terminate the given share membership if the user actually owns it.
		if session != nil && slices.Contains(session.Targets, lid) {
			if share := b.GetShare(lid); share != nil {
				switch share.Type {
				case ShareTypeAlone:
					b.deleteShare(share)
				case ShareTypeGroup:
					share.RemoveHost(session)
					b.CleanGroup(share)
				}
			}
			session.RemoveTarget(lid)
			b.SaveSession(session)
		}
	} else if session != nil {
		b.EndSession(session)
	}
	c.write("OK\n")
}

// handleAdopt adds the host of a single-user share to a group share.
func (b *Backend) handleAdopt(c *ctx) {
	if !c.requirePOST("sid", "nic", "aid", "pin") {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	sid, _ := c.post("sid")
	if b.GetSession(sid) == nil {
		c.write(c.lang.T("session_expired") + "\n")
		return
	}

	aid, _ := c.post("aid")
	share := b.GetShare(aid)
	if share == nil {
		c.write(c.lang.T("share_not_found") + "\n")
		return
	}
	if share.Type != ShareTypeAlone {
		c.write(c.lang.T("group_share_not_adoptable") + "\n")
		return
	}
	if !share.IsAdoptable() {
		c.write(c.lang.T("share_adoption_not_allowed") + "\n")
		return
	}
	host := b.GetSession(share.Host)
	if host != nil && host.IsEncrypted() {
		c.write(c.lang.T("e2e_adoption_not_allowed") + "\n")
		return
	}

	pin, _ := c.post("pin")
	target := b.GetShareByPIN(pin)
	if target == nil {
		c.write(c.lang.T("session_expired") + "\n")
		return
	}

	if host != nil {
		nick, _ := c.post("nic")
		b.AddHost(target, nick, host)
		b.SaveShare(target)
		host.AddTarget(target)
		b.SaveSession(host)
	}
	c.write("OK\n")
}

// handleNewLink creates an additional single-user share for a session.
func (b *Backend) handleNewLink(c *ctx) {
	if !c.requirePOST("sid", "ado") {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	sid, _ := c.post("sid")
	session := b.GetSession(sid)
	if session == nil {
		c.write(c.lang.T("session_expired") + "\n")
		return
	}

	ado, _ := c.post("ado")
	share := b.NewSoloShare()
	share.Adoptable = phpIntval(ado)
	share.Host = session.ID
	share.Expire = session.Expire
	b.SaveShare(share)

	session.AddTarget(share)
	b.SaveSession(session)
	c.writeLines("OK", b.ViewLink(share), share.ID)
}

// handleDynamicJS returns configuration needed by the frontend as JavaScript.
func (b *Backend) handleDynamicJS(w http.ResponseWriter, r *http.Request) {
	vars := []struct {
		name  string
		value any
	}{
		{"TILE_URI", b.cfg.MapTileURI},
		{"ATTRIBUTION", b.cfg.MapAttribution},
		{"DEFAULT_ZOOM", b.cfg.DefaultZoom},
		{"MAX_ZOOM", b.cfg.MaxZoom},
		{"MAX_POINTS", b.cfg.MaxShownPts},
		{"VELOCITY_DELTA_TIME", b.cfg.VDataPoints},
		{"TRAIL_COLOR", b.cfg.TrailColor},
		{"VELOCITY_UNIT", b.cfg.VelocityUnit},
		{"OFFLINE_TIMEOUT", b.cfg.OfflineTimeout},
		{"REQUEST_TIMEOUT", b.cfg.RequestTimeout},
	}
	var sb strings.Builder
	for _, v := range vars {
		value, err := json.Marshal(v.value)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		sb.WriteString("var " + v.name + " = " + string(value) + ";\n")
	}
	w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
	_, _ = w.Write([]byte(sb.String()))
}
