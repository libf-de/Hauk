package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

// testServer wraps a backend with a controllable clock.
type testServer struct {
	t       *testing.T
	backend *Backend
	handler http.Handler
	now     time.Time
}

func newTestServer(t *testing.T, modify func(*Config)) *testServer {
	t.Helper()
	cfg := DefaultConfig()
	cfg.PublicURL = "https://hauk.example/"
	if modify != nil {
		modify(&cfg)
	}
	ts := &testServer{t: t, backend: NewBackend(cfg), now: time.Unix(1_800_000_000, 0)}
	ts.backend.now = func() time.Time { return ts.now }
	ts.handler = ts.backend.Handler()
	return ts
}

func (ts *testServer) do(req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	ts.handler.ServeHTTP(rec, req)
	return rec
}

// post sends a form POST request and returns the response lines.
func (ts *testServer) post(path string, form url.Values) []string {
	ts.t.Helper()
	req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := ts.do(req)
	if got := rec.Header().Get("X-Hauk-Version"); got != BackendVersion {
		ts.t.Errorf("%s: X-Hauk-Version = %q", path, got)
	}
	return strings.Split(strings.TrimSuffix(rec.Body.String(), "\n"), "\n")
}

// fetch requests fetch.php and decodes the JSON response.
func (ts *testServer) fetch(query string) (int, map[string]any) {
	ts.t.Helper()
	rec := ts.do(httptest.NewRequest(http.MethodGet, "/api/fetch.php?"+query, nil))
	if rec.Code != http.StatusOK {
		return rec.Code, nil
	}
	var data map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &data); err != nil {
		ts.t.Fatalf("invalid JSON from fetch.php: %v\n%s", err, rec.Body.String())
	}
	return rec.Code, data
}

func (ts *testServer) createSolo(extra url.Values) (sid, shareID string) {
	ts.t.Helper()
	form := url.Values{"pwd": {""}, "dur": {"3600"}, "int": {"5"}}
	for k, v := range extra {
		form[k] = v
	}
	lines := ts.post("/api/create.php", form)
	if lines[0] != "OK" || len(lines) != 4 {
		ts.t.Fatalf("create failed: %q", lines)
	}
	if lines[2] != "https://hauk.example/?"+lines[3] {
		ts.t.Errorf("view link %q does not match share ID %q", lines[2], lines[3])
	}
	return lines[1], lines[3]
}

func expectLines(t *testing.T, got []string, want ...string) {
	t.Helper()
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Errorf("got %q, want %q", got, want)
	}
}

func TestDefaultPasswordHashIsEmptyPassword(t *testing.T) {
	if !verifyPassword("", DefaultPasswordHash) {
		t.Fatal("default password hash should match the empty password")
	}
}

func TestCreateValidation(t *testing.T) {
	ts := newTestServer(t, func(c *Config) { c.PasswordHash = "$1$saltsalt$9xy1btjgzLYfb7hivXtC//" })
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}}), "Missing data!")
	expectLines(t, ts.post("/api/create.php", url.Values{"dur": {"60"}, "int": {"5"}}), "Missing data!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"wrong"}, "dur": {"60"}, "int": {"5"}}), "Incorrect password!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"100000"}, "int": {"5"}}), "Share period is too long!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"60"}, "int": {"0.5"}}), "Update interval is too short!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"60"}, "int": {"5"}, "mod": {"1"}}), "Missing data!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"60"}, "int": {"5"}, "mod": {"1"}, "nic": {"a"}, "e2e": {"1"}}), "Group shares cannot be password protected!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"60"}, "int": {"5"}, "e2e": {"1"}}), "Missing data!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"60"}, "int": {"5"}, "mod": {"9"}}), "Unsupported share mode!")
}

func TestLocalizedMessages(t *testing.T) {
	ts := newTestServer(t, nil)
	req := httptest.NewRequest(http.MethodPost, "/api/post.php", strings.NewReader("sid=x&lat=1&lon=1&time=1"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
	if got := ts.do(req).Body.String(); got != "Sitzung abgelaufen!\n" {
		t.Errorf("got %q", got)
	}
}

func TestSoloShareLifecycle(t *testing.T) {
	ts := newTestServer(t, nil)
	sid, shareID := ts.createSolo(nil)
	if !regexp.MustCompile(`^[1-9A-NP-Z]{4}-[1-9A-NP-Z]{4}$`).MatchString(shareID) {
		t.Errorf("unexpected share ID format %q", shareID)
	}

	code, data := ts.fetch("id=" + shareID)
	if code != http.StatusOK || data["type"] != float64(ShareTypeAlone) || data["interval"] != float64(5) || data["encrypted"] != false {
		t.Fatalf("unexpected fetch result %d %v", code, data)
	}
	if data["nav"] != nil || len(data["points"].([]any)) != 0 {
		t.Errorf("new share should have no points or navigation data: %v", data)
	}

	expectLines(t, ts.post("/api/post.php", url.Values{"sid": {sid}, "lat": {"91"}, "lon": {"0"}, "time": {"1"}}), "Invalid location!")
	for i, time := range []string{"100", "101", "102", "103"} {
		form := url.Values{"sid": {sid}, "lat": {"52.5"}, "lon": {"13.4"}, "time": {time}, "prv": {"1"}}
		if i == 3 {
			form.Set("spd", "12.5")
			form.Set("acc", "8")
		}
		expectLines(t, ts.post("/api/post.php", form), "OK", "https://hauk.example/?%s", shareID)
	}

	// Only max_cached_pts points are kept.
	_, data = ts.fetch("id=" + shareID)
	points := data["points"].([]any)
	if len(points) != 3 {
		t.Fatalf("expected 3 points, got %v", points)
	}
	last, _ := json.Marshal(points[2])
	if string(last) != `[52.5,13.4,103,1,8,12.5]` {
		t.Errorf("unexpected point %s", last)
	}
	first, _ := json.Marshal(points[0])
	if string(first) != `[52.5,13.4,101,1,null,null]` {
		t.Errorf("unexpected point %s", first)
	}

	_, data = ts.fetch("id=" + shareID + "&since=102")
	if len(data["points"].([]any)) != 1 {
		t.Errorf("expected only points newer than since: %v", data["points"])
	}

	// A second link for the same session.
	lines := ts.post("/api/new-link.php", url.Values{"sid": {sid}, "ado": {"1"}})
	if lines[0] != "OK" || lines[1] != "https://hauk.example/?"+lines[2] {
		t.Fatalf("new-link failed: %q", lines)
	}
	expectLines(t, ts.post("/api/post.php", url.Values{"sid": {sid}, "lat": {"1"}, "lon": {"1"}, "time": {"104"}}), "OK", "https://hauk.example/?%s", shareID+","+lines[2])

	// Stopping one link keeps the other.
	expectLines(t, ts.post("/api/stop.php", url.Values{"sid": {sid}, "lid": {shareID}}), "OK")
	if code, _ := ts.fetch("id=" + shareID); code != http.StatusNotFound {
		t.Errorf("stopped share should be gone, got %d", code)
	}
	if code, _ := ts.fetch("id=" + lines[2]); code != http.StatusOK {
		t.Errorf("other share should still exist, got %d", code)
	}

	// Stopping the session ends everything.
	expectLines(t, ts.post("/api/stop.php", url.Values{"sid": {sid}}), "OK")
	if code, _ := ts.fetch("id=" + lines[2]); code != http.StatusNotFound {
		t.Errorf("share should be gone after stopping the session, got %d", code)
	}
	expectLines(t, ts.post("/api/post.php", url.Values{"sid": {sid}, "lat": {"1"}, "lon": {"1"}, "time": {"105"}}), "Session expired!")
}

func TestExpiry(t *testing.T) {
	ts := newTestServer(t, nil)
	sid, shareID := ts.createSolo(url.Values{"dur": {"60"}})
	ts.now = ts.now.Add(61 * time.Second)
	if code, _ := ts.fetch("id=" + shareID); code != http.StatusNotFound {
		t.Errorf("expired share should not be found, got %d", code)
	}
	expectLines(t, ts.post("/api/post.php", url.Values{"sid": {sid}, "lat": {"1"}, "lon": {"1"}, "time": {"1"}}), "Session expired!")
	ts.backend.Purge()
	if len(ts.backend.sessions) != 0 || len(ts.backend.shares) != 0 {
		t.Errorf("purge should remove expired data")
	}
}

func TestNavigation(t *testing.T) {
	ts := newTestServer(t, nil)
	sid, shareID := ts.createSolo(nil)
	base := url.Values{"sid": {sid}, "lat": {"52.5"}, "lon": {"13.4"}, "time": {"100"}}
	with := func(kv ...string) url.Values {
		form := url.Values{}
		for k, v := range base {
			form[k] = v
		}
		for i := 0; i < len(kv); i += 2 {
			form.Set(kv[i], kv[i+1])
		}
		return form
	}

	expectLines(t, ts.post("/api/post.php", with("nrt", "_p~iF~ps|U_ulLnnqC", "neta", "1800001500", "ndst", "12345")), "OK", "https://hauk.example/?%s", shareID)
	_, data := ts.fetch("id=" + shareID)
	nav := data["nav"].(map[string]any)
	rid, _ := nav["rid"].(string)
	if len(rid) != 8 || nav["route"] != "_p~iF~ps|U_ulLnnqC" || nav["eta"] != float64(1800001500) || nav["dist"] != float64(12345) || nav["time"] != float64(1800000000) {
		t.Fatalf("unexpected nav %v", nav)
	}

	// The route is omitted if the client already has it.
	_, data = ts.fetch("id=" + shareID + "&rids=" + rid)
	nav = data["nav"].(map[string]any)
	if _, ok := nav["route"]; ok {
		t.Errorf("route should be omitted for known route IDs: %v", nav)
	}

	// Invalid navigation data is ignored, but the location is stored.
	expectLines(t, ts.post("/api/post.php", with("time", "101", "nrt", "invalid route!", "neta", "soon", "ndst", "far")), "OK", "https://hauk.example/?%s", shareID)
	_, data = ts.fetch("id=" + shareID)
	nav = data["nav"].(map[string]any)
	if nav["rid"] != rid || nav["eta"] != float64(1800001500) || len(data["points"].([]any)) != 2 {
		t.Errorf("invalid navigation data should be ignored: %v", data)
	}

	// A new route gets a new route ID.
	ts.post("/api/post.php", with("time", "102", "nrt", "_ulLnnqC"))
	_, data = ts.fetch("id=" + shareID + "&rids=" + rid)
	nav = data["nav"].(map[string]any)
	if nav["rid"] == rid || nav["route"] != "_ulLnnqC" {
		t.Errorf("new route should be sent with a new ID: %v", nav)
	}

	// Ending navigation clears all navigation data.
	ts.post("/api/post.php", with("time", "103", "nend", "1"))
	_, data = ts.fetch("id=" + shareID)
	if data["nav"] != nil {
		t.Errorf("navigation data should be cleared: %v", data["nav"])
	}
}

func TestCameraStreams(t *testing.T) {
	ts := newTestServer(t, nil)
	sid, shareID := ts.createSolo(nil)
	base := url.Values{"sid": {sid}, "lat": {"52.5"}, "lon": {"13.4"}, "time": {"100"}}

	_, data := ts.fetch("id=" + shareID)
	if data["cams"] != nil {
		t.Errorf("new share should have no cameras: %v", data["cams"])
	}

	// Entries without an absolute HTTP(S) URL are dropped.
	form := url.Values{"cams": {`[{"name":" Front ","url":"https://cam.example/s/abc"},{"name":"Bad","url":"javascript:alert(1)"},{"name":"Rel","url":"/s/x"},{"name":"Rear","url":"http://cam.example/s/def"}]`}}
	for k, v := range base {
		form[k] = v
	}
	expectLines(t, ts.post("/api/post.php", form), "OK", "https://hauk.example/?%s", shareID)
	_, data = ts.fetch("id=" + shareID)
	cams, _ := json.Marshal(data["cams"])
	if string(cams) != `{"iv":null,"list":[{"name":"Front","url":"https://cam.example/s/abc"},{"name":"Rear","url":"http://cam.example/s/def"}]}` {
		t.Errorf("unexpected cameras %s", cams)
	}

	// The cameras are kept by later updates without camera data.
	base.Set("time", "101")
	ts.post("/api/post.php", base)
	_, data = ts.fetch("id=" + shareID)
	if data["cams"] == nil {
		t.Errorf("cameras should be kept")
	}

	// Invalid camera data clears the cameras, but the location is stored.
	form.Set("time", "102")
	form.Set("cams", "not json")
	expectLines(t, ts.post("/api/post.php", form), "OK", "https://hauk.example/?%s", shareID)
	_, data = ts.fetch("id=" + shareID)
	if data["cams"] != nil || len(data["points"].([]any)) != 3 {
		t.Errorf("invalid cameras should be cleared: %v", data)
	}

	// Encrypted camera data is stored as is, with the IV of the update.
	esid, eshareID := ts.createSolo(url.Values{"e2e": {"1"}, "salt": {"c2FsdA=="}})
	ts.post("/api/post.php", url.Values{"sid": {esid}, "iv": {"aXY="}, "lat": {"Y2lwaGVy"}, "lon": {"Y2lwaGVy"}, "time": {"MTIz"}, "cams": {"Y2Ftcw==\n"}})
	_, data = ts.fetch("id=" + eshareID)
	ecams := data["cams"].(map[string]any)
	if ecams["list"] != "Y2Ftcw==\n" || ecams["iv"] != "aXY=" {
		t.Errorf("unexpected encrypted cameras %v", ecams)
	}

	// Group shares list the cameras by nickname.
	lines := ts.post("/api/create.php", url.Values{"pwd": {""}, "dur": {"600"}, "int": {"10"}, "mod": {"1"}, "nic": {"Zoe"}})
	ts.post("/api/post.php", url.Values{"sid": {lines[1]}, "lat": {"1"}, "lon": {"2"}, "time": {"100"}, "cams": {`[{"name":"Dash","url":"https://cam.example/s/zoe"}]`}})
	_, data = ts.fetch("id=" + lines[4])
	gcams, _ := json.Marshal(data["cams"])
	if string(gcams) != `{"Zoe":{"iv":null,"list":[{"name":"Dash","url":"https://cam.example/s/zoe"}]}}` {
		t.Errorf("unexpected group cameras %s", gcams)
	}
}

// Current clients send the cameras to cams.php, also before the first location.
func TestCameraStreamsEndpoint(t *testing.T) {
	ts := newTestServer(t, nil)
	sid, shareID := ts.createSolo(nil)

	expectLines(t, ts.post("/api/cams.php", url.Values{"sid": {sid}, "cams": {`[{"name":"Front","url":"https://cam.example/s/abc"},{"name":"Bad","url":"javascript:alert(1)"}]`}}), "OK")
	_, data := ts.fetch("id=" + shareID)
	cams, _ := json.Marshal(data["cams"])
	if string(cams) != `{"iv":null,"list":[{"name":"Front","url":"https://cam.example/s/abc"}]}` || len(data["points"].([]any)) != 0 {
		t.Errorf("unexpected cameras %s, points %v", cams, data["points"])
	}

	// An empty list clears the cameras.
	expectLines(t, ts.post("/api/cams.php", url.Values{"sid": {sid}, "cams": {"[]"}}), "OK")
	if _, data = ts.fetch("id=" + shareID); data["cams"] != nil {
		t.Errorf("cameras should be cleared: %v", data["cams"])
	}

	// Oversized lists are ignored.
	ts.post("/api/cams.php", url.Values{"sid": {sid}, "cams": {`[{"name":"Front","url":"https://cam.example/s/abc"}]`}})
	ts.post("/api/cams.php", url.Values{"sid": {sid}, "cams": {"[" + strings.Repeat(" ", camsMaxLength) + "]"}})
	if _, data = ts.fetch("id=" + shareID); data["cams"] == nil {
		t.Errorf("oversized cameras should be ignored")
	}

	// Encrypted sessions need an IV.
	esid, eshareID := ts.createSolo(url.Values{"e2e": {"1"}, "salt": {"c2FsdA=="}})
	expectLines(t, ts.post("/api/cams.php", url.Values{"sid": {esid}, "cams": {"Y2Ftcw==\n"}}), "Missing data!")
	expectLines(t, ts.post("/api/cams.php", url.Values{"sid": {esid}, "iv": {"aXY="}, "cams": {"Y2Ftcw==\n"}}), "OK")
	_, data = ts.fetch("id=" + eshareID)
	if ecams := data["cams"].(map[string]any); ecams["list"] != "Y2Ftcw==\n" || ecams["iv"] != "aXY=" {
		t.Errorf("unexpected encrypted cameras %v", ecams)
	}

	expectLines(t, ts.post("/api/cams.php", url.Values{"sid": {"unknown"}, "cams": {"[]"}}), "Session expired!")
}

func TestCacheHeaders(t *testing.T) {
	ts := newTestServer(t, nil)
	for path, want := range map[string]string{
		"/dynamic.js.php": "no-cache",
		"/main.js":        "no-cache",
	} {
		if got := ts.do(httptest.NewRequest(http.MethodGet, path, nil)).Header().Get("Cache-Control"); got != want {
			t.Errorf("%s: Cache-Control = %q, want %q", path, got, want)
		}
	}
}

func TestEncryptedShare(t *testing.T) {
	ts := newTestServer(t, nil)
	sid, shareID := ts.createSolo(url.Values{"e2e": {"1"}, "salt": {"c2FsdA=="}, "ado": {"1"}})
	form := url.Values{"sid": {sid}, "lat": {"Y2lwaGVy"}, "lon": {"Y2lwaGVy"}, "time": {"MTIz"}, "prv": {"cHJ2"}, "nrt": {"cm91dGU=\n"}, "neta": {"ZXRh"}, "ndst": {"ZGlzdA=="}}
	expectLines(t, ts.post("/api/post.php", form), "Missing data!")
	form.Set("iv", "aXY=")
	expectLines(t, ts.post("/api/post.php", form), "OK", "https://hauk.example/?%s", shareID)

	// Encrypted timestamps cannot be compared, so all points are returned.
	_, data := ts.fetch("id=" + shareID + "&since=1800000000")
	if data["encrypted"] != true || data["salt"] != "c2FsdA==" {
		t.Errorf("unexpected encryption fields: %v", data)
	}
	point, _ := json.Marshal(data["points"])
	if string(point) != `[["aXY=","Y2lwaGVy","Y2lwaGVy","MTIz","cHJ2",null,null]]` {
		t.Errorf("unexpected points %s", point)
	}
	nav := data["nav"].(map[string]any)
	if nav["route"] != "cm91dGU=\n" || nav["riv"] != "aXY=" || nav["eta"] != "ZXRh" || nav["dist"] != "ZGlzdA==" || nav["iv"] != "aXY=" {
		t.Errorf("unexpected encrypted nav %v", nav)
	}

	// Encrypted shares cannot be adopted.
	group := ts.post("/api/create.php", url.Values{"pwd": {""}, "dur": {"60"}, "int": {"1"}, "mod": {"1"}, "nic": {"g"}})
	expectLines(t, ts.post("/api/adopt.php", url.Values{"sid": {group[1]}, "nic": {"x"}, "aid": {shareID}, "pin": {group[3]}}), "This share is password protected and cannot be adopted!")
}

func TestGroupShare(t *testing.T) {
	ts := newTestServer(t, nil)
	lines := ts.post("/api/create.php", url.Values{"pwd": {""}, "dur": {"600"}, "int": {"10"}, "mod": {"1"}, "nic": {"Zoe"}})
	if lines[0] != "OK" || len(lines) != 5 || !regexp.MustCompile(`^\d{6}$`).MatchString(lines[3]) {
		t.Fatalf("group create failed: %q", lines)
	}
	zoe, pin, shareID := lines[1], lines[3], lines[4]

	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {""}, "dur": {"60"}, "int": {"5"}, "mod": {"2"}, "nic": {"Adam"}, "pin": {"000000"}}), "Invalid group PIN!")
	lines = ts.post("/api/create.php", url.Values{"pwd": {""}, "dur": {"1200"}, "int": {"5"}, "mod": {"2"}, "nic": {"Adam"}, "pin": {pin}})
	if lines[0] != "OK" || lines[3] != shareID {
		t.Fatalf("join failed: %q", lines)
	}
	adam := lines[1]

	ts.post("/api/post.php", url.Values{"sid": {zoe}, "lat": {"1"}, "lon": {"2"}, "time": {"100"}})
	ts.post("/api/post.php", url.Values{"sid": {adam}, "lat": {"3"}, "lon": {"4"}, "time": {"100"}, "neta": {"1800000900"}, "ndst": {"500"}})

	rec := ts.do(httptest.NewRequest(http.MethodGet, "/api/fetch.php?id="+shareID, nil))
	body := rec.Body.String()
	// Participants are listed in the order they joined.
	if !strings.Contains(body, `"points":{"Zoe":[[1,2,100,0,null,null]],"Adam":[[3,4,100,0,null,null]]}`) {
		t.Errorf("unexpected group points: %s", body)
	}
	var data map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &data)
	// The share lasts as long as the longest participating session, and
	// uses the shortest interval.
	if data["type"] != float64(ShareTypeGroup) || data["expire"] != float64(1800001200) || data["interval"] != float64(5) {
		t.Errorf("unexpected group share data: %v", data)
	}
	navs := data["nav"].(map[string]any)
	if _, ok := navs["Zoe"]; ok || navs["Adam"].(map[string]any)["dist"] != float64(500) {
		t.Errorf("unexpected group nav: %v", navs)
	}

	// Adopt a solo share into the group.
	solo, soloShare := ts.createSolo(url.Values{"ado": {"1"}})
	expectLines(t, ts.post("/api/adopt.php", url.Values{"sid": {zoe}, "nic": {"Solo"}, "aid": {soloShare}, "pin": {pin}}), "OK")
	expectLines(t, ts.post("/api/post.php", url.Values{"sid": {solo}, "lat": {"5"}, "lon": {"6"}, "time": {"100"}}), "OK", "https://hauk.example/?%s", soloShare+","+shareID)
	_, data = ts.fetch("id=" + shareID)
	if len(data["points"].(map[string]any)) != 3 {
		t.Errorf("adopted user should be part of the group: %v", data["points"])
	}

	_, notAdoptable := ts.createSolo(nil)
	expectLines(t, ts.post("/api/adopt.php", url.Values{"sid": {zoe}, "nic": {"X"}, "aid": {notAdoptable}, "pin": {pin}}), "The host of the given share does not permit adoption!")
	expectLines(t, ts.post("/api/adopt.php", url.Values{"sid": {zoe}, "nic": {"X"}, "aid": {shareID}, "pin": {pin}}), "You cannot adopt group shares!")

	// Leaving the group removes the participant; the group ends with its
	// last participant.
	expectLines(t, ts.post("/api/stop.php", url.Values{"sid": {adam}, "lid": {shareID}}), "OK")
	expectLines(t, ts.post("/api/stop.php", url.Values{"sid": {solo}}), "OK")
	_, data = ts.fetch("id=" + shareID)
	if points := data["points"].(map[string]any); len(points) != 1 || data["expire"] != float64(1800000600) {
		t.Errorf("only Zoe should remain, with her expiration time: %v", data)
	}
	expectLines(t, ts.post("/api/stop.php", url.Values{"sid": {zoe}}), "OK")
	if code, _ := ts.fetch("id=" + shareID); code != http.StatusNotFound {
		t.Errorf("group share should end with its last participant, got %d", code)
	}
	if ts.backend.GetShareByPIN(pin) != nil {
		t.Errorf("group PIN should be released")
	}
}

func TestCustomLinks(t *testing.T) {
	ts := newTestServer(t, func(c *Config) {
		c.ReservedLinks = map[string][]string{"WheresAlice": {"alice"}}
	})
	_, id := ts.createSolo(url.Values{"lid": {"MyLink"}})
	if id != "MyLink" {
		t.Errorf("custom link should be honored, got %q", id)
	}
	if _, id = ts.createSolo(url.Values{"lid": {"MyLink"}}); id == "MyLink" {
		t.Errorf("custom link in use should not be reused")
	}
	if _, id = ts.createSolo(url.Values{"lid": {"bad link!"}}); id == "bad link!" {
		t.Errorf("invalid custom link should be ignored")
	}
	if _, id = ts.createSolo(url.Values{"lid": {"WheresAlice"}, "usr": {"bob"}}); id == "WheresAlice" {
		t.Errorf("reserved link should not be given to bob")
	}
	if _, id = ts.createSolo(url.Values{"lid": {"WheresAlice"}, "usr": {"alice"}}); id != "WheresAlice" {
		t.Errorf("reserved link should be given to alice, got %q", id)
	}

	ts = newTestServer(t, func(c *Config) { c.ReserveWhitelist = true })
	if _, id = ts.createSolo(url.Values{"lid": {"MyLink"}}); id == "MyLink" {
		t.Errorf("whitelist mode should reject unreserved links")
	}
	ts = newTestServer(t, func(c *Config) { c.AllowLinkReq = false })
	if _, id = ts.createSolo(url.Values{"lid": {"MyLink"}}); id == "MyLink" {
		t.Errorf("custom links should be disabled")
	}
}

func TestHtpasswdAuth(t *testing.T) {
	path := filepath.Join(t.TempDir(), "users.htpasswd")
	content := strings.Join([]string{
		"alice:$2y$05$Au5N99/tOXRqwgYvBiqHC.vVE9qf7PbvzQDzYHmCavpBF1e6sVnsa",
		"bob:$apr1$saltsalt$LrttParrLPdxvgutaSXWJ0",
		"carol:{SHA}5en6G6MezRroT3XKqkdPOmY/BfQ=",
		"dave:$6$saltsalt$TVLlQcbpFVof5W3Yz4DTP6gRstiNuHwwTt6GLc1E5n0U0aDehy0S5knV8wiOQSpT0Y77vwPZN.Pq.H91p5hVO1",
		"",
	}, "\n")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	ts := newTestServer(t, func(c *Config) {
		c.AuthMethod = AuthHtpasswd
		c.HtpasswdPath = path
	})
	form := func(user, password string) url.Values {
		return url.Values{"usr": {user}, "pwd": {password}, "dur": {"60"}, "int": {"5"}}
	}
	for _, user := range []string{"alice", "bob", "carol", "dave"} {
		if lines := ts.post("/api/create.php", form(user, "secret")); lines[0] != "OK" {
			t.Errorf("%s should be able to log in: %q", user, lines)
		}
		expectLines(t, ts.post("/api/create.php", form(user, "wrong")), "Incorrect password!")
	}
	expectLines(t, ts.post("/api/create.php", form("eve", "secret")), "Incorrect password!")
	expectLines(t, ts.post("/api/create.php", url.Values{"pwd": {"secret"}, "dur": {"60"}, "int": {"5"}}), "Username required!")

	ts.backend.cfg.HtpasswdPath = filepath.Join(t.TempDir(), "missing")
	expectLines(t, ts.post("/api/create.php", form("alice", "secret")), "Cannot find password file!")
}

func TestPasswordHashFormats(t *testing.T) {
	for _, hash := range []string{
		"$1$saltsalt$9xy1btjgzLYfb7hivXtC//",
		"$apr1$saltsalt$LrttParrLPdxvgutaSXWJ0",
		"$5$saltsalt$0IyaXrmV7.sGNS6tirgqHLqX/G.FBvgkYA.lpPdS5sA",
		"$6$saltsalt$TVLlQcbpFVof5W3Yz4DTP6gRstiNuHwwTt6GLc1E5n0U0aDehy0S5knV8wiOQSpT0Y77vwPZN.Pq.H91p5hVO1",
		"$2y$05$Au5N99/tOXRqwgYvBiqHC.vVE9qf7PbvzQDzYHmCavpBF1e6sVnsa",
		"{SHA}5en6G6MezRroT3XKqkdPOmY/BfQ=",
	} {
		if !verifyPassword("secret", hash) || verifyPassword("wrong", hash) {
			t.Errorf("verification failed for %s", hash)
		}
	}
	if verifyPassword("secret", "plaintext") {
		t.Errorf("unsupported hash formats must not verify")
	}
}

func TestLinkStyles(t *testing.T) {
	patterns := map[LinkStyle]string{
		Link4Plus4UpperCase: `^[1-9A-NP-Z]{4}-[1-9A-NP-Z]{4}$`,
		Link4Plus4LowerCase: `^[0-9a-z]{4}-[0-9a-z]{4}$`,
		Link4Plus4MixedCase: `^[1-9A-HJ-NP-Za-km-z]{4}-[1-9A-HJ-NP-Za-km-z]{4}$`,
		LinkUUIDv4:          `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`,
		Link16Hex:           `^[0-9a-f]{16}$`,
		Link16UpperCase:     `^[1-9A-NP-Z]{16}$`,
		Link16LowerCase:     `^[0-9a-z]{16}$`,
		Link16MixedCase:     `^[1-9A-HJ-NP-Za-km-z]{16}$`,
		Link32Hex:           `^[0-9a-f]{32}$`,
		Link32UpperCase:     `^[1-9A-NP-Z]{32}$`,
		Link32LowerCase:     `^[0-9a-z]{32}$`,
		Link32MixedCase:     `^[1-9A-HJ-NP-Za-km-z]{32}$`,
	}
	for style, pattern := range patterns {
		re := regexp.MustCompile(pattern)
		for i := 0; i < 50; i++ {
			if id := newLinkID(style); !re.MatchString(id) {
				t.Errorf("%s: %q does not match %s", linkStyleNames[style], id, pattern)
			}
		}
	}
}

func TestNegotiateLang(t *testing.T) {
	for header, want := range map[string]Lang{
		"":                      "en",
		"de-DE,de;q=0.9":        "de",
		"nb-NO":                 "nb_NO",
		"nb":                    "nb_NO",
		"fr-CA;q=0.5,it;q=0.8":  "it",
		"xx,es;q=0.9":           "en",
		"pl":                    "pl",
		"en-US,en;q=0.9,de;q=1": "de",
	} {
		if got := negotiateLang(header); got != want {
			t.Errorf("negotiateLang(%q) = %q, want %q", header, got, want)
		}
	}
	if Lang("eu").T("ldap_search_ambiguous") == "" {
		t.Errorf("missing translations should fall back to English")
	}
}

func TestLoadConfig(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	yaml := `
auth_method: HTPASSWD
link_style: LINK_16_HEX
velocity_unit: MILES_PER_HOUR
reserved_links:
  WheresAlice: [alice]
trail_color: '#00ff00'
public_url: https://file.example/
`
	if err := os.WriteFile(path, []byte(yaml), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, _, err := LoadConfig(path, []string{"HAUK_PUBLIC_URL=https://env.example/", "HAUK_MAX_ZOOM=17", "HAUK_LINK_STYLE=uuid_v4", "HAUK_TRAIL_COLOR=#123456", "HAUK_HIDE_LOGO=true", "OTHER=1"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AuthMethod != AuthHtpasswd || cfg.LinkStyle != LinkUUIDv4 || cfg.VelocityUnit != MilesPerHour ||
		cfg.PublicURL != "https://env.example/" || cfg.MaxZoom != 17 || cfg.TrailColor != "#123456" || !cfg.HideLogo ||
		cfg.ReservedLinks["WheresAlice"][0] != "alice" || cfg.DefaultZoom != 14 {
		t.Errorf("unexpected config %+v", cfg)
	}

	if err := os.WriteFile(path, []byte("storage_backend: 0\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := LoadConfig(path, nil); err == nil {
		t.Errorf("unknown options should be rejected")
	}
	if err := os.WriteFile(path, []byte("auth_method: LDAP\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := LoadConfig(path, nil); err == nil || !strings.Contains(err.Error(), "LDAP") {
		t.Errorf("LDAP should be rejected with a clear error, got %v", err)
	}

	t.Chdir(dir)
	os.Remove(path)
	if _, _, err := LoadConfig("", nil); err == nil {
		t.Errorf("running without any configuration should fail")
	}
	if cfg, _, err := LoadConfig("", []string{"HAUK_PASSWORD_HASH=$1$x$y"}); err != nil || cfg.PasswordHash != "$1$x$y" {
		t.Errorf("configuration from environment only should work: %v", err)
	}
}

func TestDynamicJSAndFrontend(t *testing.T) {
	ts := newTestServer(t, func(c *Config) { c.VelocityUnit = MetersPerSecond })
	rec := ts.do(httptest.NewRequest(http.MethodGet, "/dynamic.js.php", nil))
	body := rec.Body.String()
	for _, want := range []string{
		`var TILE_URI = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";`,
		`var HIDE_LOGO = false;`,
		`var HIDE_LOCATE_BUTTON = false;`,
		`var HIDE_USER_LIST_BUTTON = false;`,
		`var VELOCITY_UNIT = {"mpsMultiplier":1,"unit":"m/s"};`,
		`var OFFLINE_TIMEOUT = 30;`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("dynamic.js.php is missing %q:\n%s", want, body)
		}
	}
	if ct := rec.Header().Get("Content-Type"); ct != "text/javascript; charset=utf-8" {
		t.Errorf("unexpected content type %q", ct)
	}

	rec = ts.do(httptest.NewRequest(http.MethodGet, "/?ABCD-1234", nil))
	html, _ := io.ReadAll(rec.Body)
	if rec.Code != http.StatusOK || !strings.Contains(string(html), "./main.js") {
		t.Errorf("frontend index not served: %d", rec.Code)
	}
	if rec := ts.do(httptest.NewRequest(http.MethodGet, "/assets/lang/en.json", nil)); rec.Code != http.StatusOK {
		t.Errorf("frontend assets not served: %d", rec.Code)
	}
}

func TestPHPConversions(t *testing.T) {
	floats := map[string]float64{"1.5": 1.5, " 2abc": 2, "abc": 0, "-3e2x": -300, ".5": 0.5, "": 0}
	for in, want := range floats {
		if got := phpFloatval(in); got != want {
			t.Errorf("phpFloatval(%q) = %v, want %v", in, got, want)
		}
	}
	ints := map[string]int64{"12": 12, "12abc": 12, "1.9": 1, "abc": 0, "-4": -4, "1e3": 1000}
	for in, want := range ints {
		if got := phpIntval(in); got != want {
			t.Errorf("phpIntval(%q) = %v, want %v", in, got, want)
		}
	}
	for in, want := range map[string]bool{"1": true, " 1.5 ": true, "1e5": true, "abc": false, "": false, "NaN": false, "0x1A": false} {
		if got := isNumeric(in); got != want {
			t.Errorf("isNumeric(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestShareIDPathRedirect(t *testing.T) {
	ts := newTestServer(t, nil)
	for path, want := range map[string]string{
		"/1234-ABCD":         "./?1234-ABCD",
		"/WheresAlice":       "./?WheresAlice",
		"/1234-ABCD?foo=bar": "./?1234-ABCD",
	} {
		rec := ts.do(httptest.NewRequest(http.MethodGet, path, nil))
		if rec.Code != http.StatusFound || rec.Header().Get("Location") != want {
			t.Errorf("%s: got %d %q, want redirect to %q", path, rec.Code, rec.Header().Get("Location"), want)
		}
	}

	// Frontend files and directories, nested paths and other methods are not
	// redirected.
	for _, req := range []*http.Request{
		httptest.NewRequest(http.MethodGet, "/main.js", nil),
		httptest.NewRequest(http.MethodGet, "/assets", nil),
		httptest.NewRequest(http.MethodGet, "/assets/lang", nil),
		httptest.NewRequest(http.MethodGet, "/a/b", nil),
		httptest.NewRequest(http.MethodGet, "/bad%20id", nil),
		httptest.NewRequest(http.MethodPost, "/1234-ABCD", nil),
	} {
		if rec := ts.do(req); rec.Header().Get("Location") == "./?"+strings.TrimPrefix(req.URL.Path, "/") {
			t.Errorf("%s %s should not be redirected to a share", req.Method, req.URL.Path)
		}
	}
	if rec := ts.do(httptest.NewRequest(http.MethodGet, "/main.js", nil)); rec.Code != http.StatusOK {
		t.Errorf("/main.js should be served, got %d", rec.Code)
	}
}
