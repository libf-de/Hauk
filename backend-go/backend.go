package main

import (
	"crypto/rand"
	"encoding/hex"
	"math"
	"math/big"
	"slices"
	"strconv"
	"sync"
	"time"
)

// Share types. Not the same as the share modes used when creating shares.
const (
	ShareTypeAlone = 0
	ShareTypeGroup = 1
)

const (
	sessionIDSize  = 32
	navRouteIDSize = 4
	groupPINMin    = 100000
	groupPINMax    = 999999
)

// Session represents one user sharing their location, and contains all
// location data for that user. Equivalent to the Client class of the PHP
// backend.
type Session struct {
	ID       string
	Expire   int64
	Interval float64
	// IDs of the shares this session is contributing location data to.
	Targets []string
	// Each point is a list of latitude, longitude, timestamp, provider,
	// accuracy and speed. For end-to-end encrypted sessions, the IV is
	// prepended and all values are ciphertexts.
	Points    [][]any
	Encrypted int64
	Salt      *string
	Nav       *Nav
	Cams      *Cams
}

// Nav contains the navigation state of a session. For end-to-end encrypted
// sessions, Route, ETA and Dist are ciphertexts and RIV/IV are the IVs they
// were encrypted with.
type Nav struct {
	RID   *string
	Route *string
	RIV   *string
	ETA   any
	Dist  any
	IV    *string
	Time  *int64
}

// Cams contains the live stream links of the user's cameras. For end-to-end
// encrypted sessions, List is the ciphertext of the JSON array and IV is the IV
// it was encrypted with.
type Cams struct {
	List any
	IV   *string
}

// Camera is a camera whose live stream can be embedded in the frontend.
type Camera struct {
	Name string `json:"name"`
	URL  string `json:"url"`
}

// GroupHost is a participant of a group share.
type GroupHost struct {
	Nick      string
	SessionID string
}

// Share is a location share that can be viewed using a link. Solo shares have
// a single host session; group shares have multiple hosts and a group PIN.
type Share struct {
	ID     string
	Type   int
	Expire int64

	// Solo shares only.
	Host      string
	Adoptable int64

	// Group shares only. Hosts are ordered by the time they joined.
	Hosts []GroupHost
	PIN   int
}

type pinEntry struct {
	ShareID string
	Expire  int64
}

// Backend holds the configuration and all sessions and shares in memory.
// Callers must hold mu while calling any method.
type Backend struct {
	cfg Config
	now func() time.Time

	mu       sync.Mutex
	sessions map[string]*Session
	shares   map[string]*Share
	pins     map[string]pinEntry
}

func NewBackend(cfg Config) *Backend {
	return &Backend{
		cfg:      cfg,
		now:      time.Now,
		sessions: map[string]*Session{},
		shares:   map[string]*Share{},
		pins:     map[string]pinEntry{},
	}
}

func (b *Backend) unix() int64 {
	return b.now().Unix()
}

// Purge deletes all expired sessions, shares and group PINs.
func (b *Backend) Purge() {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := b.unix()
	for id, s := range b.sessions {
		if s.Expire <= now {
			delete(b.sessions, id)
		}
	}
	for id, sh := range b.shares {
		if sh.Expire <= now {
			delete(b.shares, id)
		}
	}
	for pin, entry := range b.pins {
		if entry.Expire <= now {
			delete(b.pins, pin)
		}
	}
}

// --- Storage ---------------------------------------------------------------

// GetSession returns the session with the given ID, or nil if it does not
// exist or has expired.
func (b *Backend) GetSession(id string) *Session {
	s, ok := b.sessions[id]
	if !ok || s.Expire <= b.unix() {
		return nil
	}
	return s
}

// SaveSession stores the session, or deletes it if it has expired.
func (b *Backend) SaveSession(s *Session) {
	if s.Expire <= b.unix() {
		delete(b.sessions, s.ID)
	} else {
		b.sessions[s.ID] = s
	}
}

// GetShare returns the share with the given ID, or nil if it does not exist or
// has expired.
func (b *Backend) GetShare(id string) *Share {
	sh, ok := b.shares[id]
	if !ok || sh.Expire <= b.unix() {
		return nil
	}
	return sh
}

// GetShareByPIN returns the group share with the given group PIN, or nil.
func (b *Backend) GetShareByPIN(pin string) *Share {
	entry, ok := b.pins[pin]
	if !ok || entry.Expire <= b.unix() {
		return nil
	}
	return b.GetShare(entry.ShareID)
}

// SaveShare stores the share, or deletes it if it has expired. Group shares
// also store their group PIN so that the share can be looked up by it.
func (b *Backend) SaveShare(sh *Share) {
	if sh.Expire <= b.unix() {
		b.deleteShare(sh)
		return
	}
	b.shares[sh.ID] = sh
	if sh.Type == ShareTypeGroup {
		b.pins[strconv.Itoa(sh.PIN)] = pinEntry{ShareID: sh.ID, Expire: sh.Expire}
	}
}

func (b *Backend) deleteShare(sh *Share) {
	delete(b.shares, sh.ID)
	if sh.Type == ShareTypeGroup {
		delete(b.pins, strconv.Itoa(sh.PIN))
	}
}

// --- Sessions --------------------------------------------------------------

// NewSession creates a new session with a random ID. It is not stored until
// SaveSession is called.
func (b *Backend) NewSession(expire int64, interval float64) *Session {
	var id string
	for {
		id = randomHex(sessionIDSize)
		if _, exists := b.sessions[id]; !exists {
			break
		}
	}
	return &Session{
		ID:       id,
		Expire:   expire,
		Interval: interval,
		Targets:  []string{},
		Points:   [][]any{},
	}
}

func (s *Session) IsEncrypted() bool {
	return s.Encrypted > 0
}

// AddTarget registers a share that this session contributes location data to.
func (s *Session) AddTarget(sh *Share) {
	s.Targets = append(s.Targets, sh.ID)
}

// RemoveTarget unregisters a share from this session.
func (s *Session) RemoveTarget(shareID string) {
	s.Targets = slices.DeleteFunc(s.Targets, func(id string) bool { return id == shareID })
}

// AddPoint adds a location point, discarding the oldest points if more than
// maxPoints are stored.
func (s *Session) AddPoint(point []any, maxPoints int) {
	s.Points = append(s.Points, point)
	if excess := len(s.Points) - maxPoints; excess > 0 {
		s.Points = s.Points[excess:]
	}
}

// GetPoints returns the stored points. If since is not nil, only points newer
// than it are returned. Timestamps of end-to-end encrypted sessions cannot be
// compared, so all points are returned for them; clients discard duplicates.
func (s *Session) GetPoints(since *float64) [][]any {
	if since == nil || s.IsEncrypted() {
		return s.Points
	}
	points := [][]any{}
	for _, point := range s.Points {
		if t, ok := point[2].(float64); ok && t > *since {
			points = append(points, point)
		}
	}
	return points
}

// EndSession deletes the session and ends or cleans up all shares it was
// contributing to.
func (b *Backend) EndSession(s *Session) {
	delete(b.sessions, s.ID)
	for _, shareID := range s.Targets {
		sh := b.GetShare(shareID)
		if sh == nil {
			continue
		}
		switch sh.Type {
		case ShareTypeAlone:
			b.deleteShare(sh)
		case ShareTypeGroup:
			b.CleanGroup(sh)
		}
	}
}

// --- Navigation ------------------------------------------------------------

func (s *Session) navData() *Nav {
	if s.Nav == nil {
		s.Nav = &Nav{}
	}
	return s.Nav
}

// SetNavRoute sets the route the user is navigating along and assigns a new
// route ID, so that clients know they have to redraw the route.
func (b *Backend) SetNavRoute(s *Session, route string, iv *string) {
	nav := s.navData()
	rid := randomHex(navRouteIDSize)
	now := b.unix()
	nav.RID = &rid
	nav.Route = &route
	nav.RIV = iv
	nav.Time = &now
}

// SetNavProgress sets the estimated time of arrival and remaining distance.
func (b *Backend) SetNavProgress(s *Session, eta, dist any, iv *string) {
	nav := s.navData()
	now := b.unix()
	nav.ETA = eta
	nav.Dist = dist
	nav.IV = iv
	nav.Time = &now
}

// ClearNav removes all navigation data, e.g. when the user has arrived.
func (s *Session) ClearNav() {
	s.Nav = nil
}

// GetNav returns the navigation data for output to clients, or nil if the user
// is not navigating. The route is omitted if its ID is in knownRouteIDs.
func (s *Session) GetNav(knownRouteIDs []string) map[string]any {
	if s.Nav == nil {
		return nil
	}
	n := s.Nav
	out := map[string]any{
		"rid":   n.RID,
		"route": n.Route,
		"riv":   n.RIV,
		"eta":   n.ETA,
		"dist":  n.Dist,
		"iv":    n.IV,
		"time":  n.Time,
	}
	if n.RID != nil && slices.Contains(knownRouteIDs, *n.RID) {
		delete(out, "route")
		delete(out, "riv")
	}
	return out
}

// --- Camera streams ----------------------------------------------------------

// SetCams sets the live stream links of the user's cameras. list is either a
// []Camera or, for end-to-end encrypted sessions, a ciphertext.
func (s *Session) SetCams(list any, iv *string) {
	s.Cams = &Cams{List: list, IV: iv}
}

// ClearCams removes all camera stream links.
func (s *Session) ClearCams() {
	s.Cams = nil
}

// GetCams returns the camera stream links for output to clients, or nil if the
// user shares no cameras.
func (s *Session) GetCams() map[string]any {
	if s.Cams == nil {
		return nil
	}
	return map[string]any{
		"list": s.Cams.List,
		"iv":   s.Cams.IV,
	}
}

// --- Shares ----------------------------------------------------------------

// NewSoloShare creates a new single-user share with a random link ID.
func (b *Backend) NewSoloShare() *Share {
	return &Share{ID: b.generateLinkID(), Type: ShareTypeAlone}
}

// NewGroupShare creates a new group share with a random link ID and PIN.
func (b *Backend) NewGroupShare() *Share {
	return &Share{ID: b.generateLinkID(), Type: ShareTypeGroup, PIN: b.generateGroupPIN()}
}

// ViewLink returns the public URL to view the share.
func (b *Backend) ViewLink(sh *Share) string {
	return b.cfg.PublicURL + "?" + sh.ID
}

// IsAdoptable returns whether the share may be adopted into a group share.
// Group shares can never be adopted.
func (sh *Share) IsAdoptable() bool {
	return sh.Type == ShareTypeAlone && sh.Adoptable != 0
}

// RequestCustomLink tries to use the given ID instead of the generated link
// ID. user is the authenticated username, or nil if no username was given.
func (b *Backend) RequestCustomLink(sh *Share, id string, user *string) bool {
	if !b.cfg.AllowLinkReq {
		return false
	}
	allowed, reserved := b.cfg.ReservedLinks[id]
	if user != nil && reserved && !slices.Contains(allowed, *user) {
		return false
	}
	if b.cfg.ReserveWhitelist && !reserved {
		return false
	}
	if b.GetShare(id) != nil {
		return false
	}
	sh.ID = id
	return true
}

// AddHost adds a participant to a group share and extends the share's
// expiration time if needed. A participant with the same nickname is
// replaced.
func (b *Backend) AddHost(sh *Share, nick string, s *Session) {
	if i := slices.IndexFunc(sh.Hosts, func(h GroupHost) bool { return h.Nick == nick }); i >= 0 {
		sh.Hosts[i].SessionID = s.ID
	} else {
		sh.Hosts = append(sh.Hosts, GroupHost{Nick: nick, SessionID: s.ID})
	}
	sh.Expire = b.AutoExpirationTime(sh)
}

// RemoveHost removes a session from a group share. Call CleanGroup afterwards.
func (sh *Share) RemoveHost(s *Session) {
	sh.Hosts = slices.DeleteFunc(sh.Hosts, func(h GroupHost) bool { return h.SessionID == s.ID })
}

// CleanGroup removes participants whose sessions have ended, and ends the share
// if no participants are left.
func (b *Backend) CleanGroup(sh *Share) {
	sh.Hosts = slices.DeleteFunc(sh.Hosts, func(h GroupHost) bool { return b.GetSession(h.SessionID) == nil })
	if len(sh.Hosts) == 0 {
		b.deleteShare(sh)
		return
	}
	// The share may now outlive all remaining sessions.
	sh.Expire = b.AutoExpirationTime(sh)
	b.SaveShare(sh)
}

// AutoExpirationTime returns the latest expiration time of all participants.
func (b *Backend) AutoExpirationTime(sh *Share) int64 {
	var expire int64
	for _, h := range sh.Hosts {
		if s := b.GetSession(h.SessionID); s != nil && s.Expire > expire {
			expire = s.Expire
		}
	}
	return expire
}

// AutoInterval returns the lowest sharing interval of all participants.
func (b *Backend) AutoInterval(sh *Share) float64 {
	interval := float64(math.MaxInt64)
	for _, h := range sh.Hosts {
		if s := b.GetSession(h.SessionID); s != nil && s.Interval < interval {
			interval = s.Interval
		}
	}
	return interval
}

// AllPoints returns the points of all participants of a group share, keyed by
// nickname.
func (b *Backend) AllPoints(sh *Share, since *float64) orderedMap {
	out := orderedMap{}
	for _, h := range sh.Hosts {
		if s := b.GetSession(h.SessionID); s != nil {
			out = append(out, keyValue{h.Nick, s.GetPoints(since)})
		}
	}
	return out
}

// AllNav returns the navigation data of all navigating participants of a group
// share, keyed by nickname.
func (b *Backend) AllNav(sh *Share, knownRouteIDs []string) orderedMap {
	out := orderedMap{}
	for _, h := range sh.Hosts {
		if s := b.GetSession(h.SessionID); s != nil {
			if nav := s.GetNav(knownRouteIDs); nav != nil {
				out = append(out, keyValue{h.Nick, nav})
			}
		}
	}
	return out
}

// AllCams returns the camera stream links of all participants of a group share
// who share cameras, keyed by nickname.
func (b *Backend) AllCams(sh *Share) orderedMap {
	out := orderedMap{}
	for _, h := range sh.Hosts {
		if s := b.GetSession(h.SessionID); s != nil {
			if cams := s.GetCams(); cams != nil {
				out = append(out, keyValue{h.Nick, cams})
			}
		}
	}
	return out
}

// --- Random IDs --------------------------------------------------------------

func randomHex(bytes int) string {
	buf := make([]byte, bytes)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	return hex.EncodeToString(buf)
}

func randomInt(n int) int {
	v, err := rand.Int(rand.Reader, big.NewInt(int64(n)))
	if err != nil {
		panic(err)
	}
	return int(v.Int64())
}

func randomString(alphabet string, length int) string {
	buf := make([]byte, length)
	for i := range buf {
		buf[i] = alphabet[randomInt(len(alphabet))]
	}
	return string(buf)
}

const (
	alphaLower = "0123456789abcdefghijklmnopqrstuvwxyz"
	// '0', 'O', 'l' and 'I' are not included because of visual similarity.
	alphaMixed = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
	// '0' and 'O' are not included because of visual similarity.
	alphaUpper = "123456789ABCDEFGHIJKLMNPQRSTUVWXYZ"
)

// generateLinkID generates an unused link ID in the configured link style.
func (b *Backend) generateLinkID() string {
	for {
		id := newLinkID(b.cfg.LinkStyle)
		if b.GetShare(id) == nil {
			return id
		}
	}
}

func newLinkID(style LinkStyle) string {
	switch style {
	case LinkUUIDv4:
		buf := make([]byte, 16)
		if _, err := rand.Read(buf); err != nil {
			panic(err)
		}
		buf[6] = buf[6]&0x0f | 0x40
		buf[8] = buf[8]&0x3f | 0x80
		h := hex.EncodeToString(buf)
		return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32]
	case Link16Hex:
		return randomHex(8)
	case Link16LowerCase:
		return randomString(alphaLower, 16)
	case Link16MixedCase:
		return randomString(alphaMixed, 16)
	case Link16UpperCase:
		return randomString(alphaUpper, 16)
	case Link32Hex:
		return randomHex(16)
	case Link32LowerCase:
		return randomString(alphaLower, 32)
	case Link32MixedCase:
		return randomString(alphaMixed, 32)
	case Link32UpperCase:
		return randomString(alphaUpper, 32)
	case Link4Plus4LowerCase:
		s := randomString(alphaLower, 8)
		return s[:4] + "-" + s[4:]
	case Link4Plus4MixedCase:
		s := randomString(alphaMixed, 8)
		return s[:4] + "-" + s[4:]
	default:
		s := randomString(alphaUpper, 8)
		return s[:4] + "-" + s[4:]
	}
}

// generateGroupPIN generates an unused 6-digit group PIN.
func (b *Backend) generateGroupPIN() int {
	for {
		pin := groupPINMin + randomInt(groupPINMax-groupPINMin+1)
		if _, exists := b.pins[strconv.Itoa(pin)]; !exists {
			return pin
		}
	}
}
