<?php

// This script is called from the Hauk app to push location updates to the
// server. Each update contains a location and timestamp from when the location
// was fetched by the client.

include("../include/inc.php");
header("X-Hauk-Version: ".BACKEND_VERSION);

requirePOST(
    "lat",  // Current latitude.
    "lon",  // Current longitude.
    "time", // Current timestamp.
    "sid"   // Session ID to post to.
);

$memcache = memConnect();

// Retrieve the session data from memcached.
$sid = $_POST["sid"];
$session = new Client($memcache, $sid);
if (!$session->exists()) die($LANG['session_expired']."\n");

if (!$session->isEncrypted()) {
    // Perform input validation.
    $lat = floatval($_POST["lat"]);
    $lon = floatval($_POST["lon"]);
    $time = floatval($_POST["time"]);
    if ($lat < -90 || $lat > 90 || $lon < -180 || $lon > 180) die($LANG['location_invalid']."\n");

    // Not all devices report speed and accuracy, but if available, report them
    // too.
    $speed = isset($_POST["spd"]) ? floatval($_POST["spd"]) : null;
    $accuracy = isset($_POST["acc"]) ? floatval($_POST["acc"]) : null;
    $provider = isset($_POST["prv"]) && $_POST["prv"] == "1" ? 1 : 0;

    // The location data object contains the sharing interval (i), duration (d)
    // and a location list (l). Each entry in the location list contains a
    // latitude, longitude, timestamp, provider, accuracy and speed, in that
    // order, as an array.
    $session->addPoint([$lat, $lon, $time, $provider, $accuracy, $speed]);

    // Navigation data is optional. The route is an encoded polyline, which
    // only consists of characters in the ASCII range 63-126. Invalid navigation
    // data is ignored rather than rejected so that it never prevents location
    // updates from being stored.
    if (isset($_POST["nrt"]) && is_string($_POST["nrt"]) && strlen($_POST["nrt"]) <= NAV_ROUTE_MAX_LENGTH && preg_match('/^[\x3F-\x7E]*$/', $_POST["nrt"])) {
        $session->setNavRoute($_POST["nrt"], null);
    }
    if (isset($_POST["neta"], $_POST["ndst"]) && is_numeric($_POST["neta"]) && is_numeric($_POST["ndst"])) {
        $session->setNavProgress(floatval($_POST["neta"]), max(0, floatval($_POST["ndst"])), null);
    }

} else {
    // Input validation cannot be performed for end-to-end encrypted data.
    $lat = $_POST["lat"];
    $lon = $_POST["lon"];
    $time = $_POST["time"];
    $speed = isset($_POST["spd"]) ? $_POST["spd"] : null;
    $accuracy = isset($_POST["acc"]) ? $_POST["acc"] : null;
    $provider = isset($_POST["prv"]) ? $_POST["prv"] : null;

    // End-to-end encrypted connections also have an IV field used to decrypt
    // the data fields.
    requirePOST("iv");
    $iv = $_POST["iv"];

    // The IV field is prepended to the array to send to the client.
    $session->addPoint([$iv, $lat, $lon, $time, $provider, $accuracy, $speed]);

    // Optional navigation data, encrypted with the same IV.
    if (isset($_POST["nrt"]) && is_string($_POST["nrt"]) && strlen($_POST["nrt"]) <= NAV_ROUTE_MAX_LENGTH) {
        $session->setNavRoute($_POST["nrt"], $iv);
    }
    if (isset($_POST["neta"], $_POST["ndst"]) && is_string($_POST["neta"]) && is_string($_POST["ndst"])) {
        $session->setNavProgress($_POST["neta"], $_POST["ndst"], $iv);
    }
}

// Clients send this flag when navigation has ended.
if (isset($_POST["nend"])) $session->clearNav();

$session->save();

if ($session->hasExpired()) {
    echo $LANG['session_expired']."\n";
} else {
    echo "OK\n".getConfig("public_url")."?%s\n".implode(",", $session->getTargetIDs())."\n";
}
