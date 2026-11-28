// An outing carried whole by the link, after the "#": never sent to the server, read here only to
// show the day, the meeting and the times, and passed on to the app's buttons (an intent link has
// no "#" of its own: the outing goes in "p").
(function () {
  "use strict";
  var root = document.getElementById("outing");
  var payload = location.hash.slice(1);
  if (!root || !/^[A-Za-z0-9_.-]{8,4096}$/.test(payload)) return;
  var labels = JSON.parse(root.getAttribute("data-labels"));
  var pkg = root.getAttribute("data-package");
  var base = root.getAttribute("data-base").split("#")[0];

  function bytesOf(text) {
    var b64 = text.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    var binary = atob(b64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function inflate(bytes) {
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
    return new Response(stream).text();
  }

  function fill(template, values) {
    return template.replace(/\{(\w+)\}/g, function (_, name) { return values[name] === undefined ? "" : String(values[name]); });
  }

  function fact(list, term, value) {
    var dt = document.createElement("dt");
    dt.textContent = term;
    var dd = document.createElement("dd");
    dd.textContent = value;
    list.appendChild(dt);
    list.appendChild(dd);
  }

  function show(outing) {
    var summary = outing.summary || {};
    var day = document.getElementById("outing-day");
    if (typeof outing.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(outing.date)) {
      var parts = outing.date.split("-");
      var date = new Date(Date.UTC(+parts[0], +parts[1] - 1, +parts[2], 12));
      var text = new Intl.DateTimeFormat(labels.lang, { weekday: "long", day: "numeric", month: "long", year: "numeric", timeZone: "UTC" }).format(date);
      day.textContent = text.charAt(0).toUpperCase() + text.slice(1);
    } else {
      day.textContent = labels.noDate;
    }
    var facts = document.getElementById("outing-facts");
    var meet = summary.meet || outing.anchorTime;
    if (meet) {
      var place = outing.meetAtPlace && outing.place && outing.place.name;
      fact(facts, labels.meet, place ? fill(labels.meetPlace, { time: meet, place: place }) : fill(labels.meetParking, { time: meet }));
    }
    if (summary.walk) fact(facts, labels.walk, summary.walk);
    if (summary.back) {
      var back = summary.backLatest && summary.backLatest !== summary.back
        ? fill(labels.backRange, { time: summary.back, latest: summary.backLatest })
        : summary.back;
      fact(facts, labels.back, back);
    }
    if (typeof outing.groupSize === "number") {
      var group = outing.groupSize === 1 ? labels.onePerson : fill(labels.people, { count: outing.groupSize });
      if (outing.groupLevel === "WITH_BEGINNERS") group += ", " + labels.beginners;
      if (outing.groupLevel === "DISCOVERY") group += ", " + labels.discovery;
      fact(facts, labels.group, group);
    }
    if (outing.withShuttle === true) fact(facts, labels.shuttle, labels.yes);
    if (typeof outing.note === "string" && outing.note.trim()) {
      var note = document.getElementById("outing-note");
      note.textContent = outing.note.trim();
      note.hidden = false;
    }
    root.hidden = false;
  }

  function passOn() {
    var full = base + "#" + payload;
    var play = "https://play.google.com/store/apps/details?id=" + encodeURIComponent(pkg) +
      "&referrer=" + encodeURIComponent("dc_link=" + encodeURIComponent(full));
    var target = base.replace(/^https:\/\//, "") + (base.indexOf("?") < 0 ? "?" : "&") + "p=" + payload;
    var anchors = document.querySelectorAll("a.button");
    for (var i = 0; i < anchors.length; i++) {
      var anchor = anchors[i];
      if (anchor.id === "open-app") {
        anchor.href = "intent://" + target + "#Intent;scheme=https;package=" + pkg + ";S.browser_fallback_url=" + encodeURIComponent(play) + ";end";
      } else if (anchor.href.indexOf("https://play.google.com/") === 0) {
        anchor.href = play;
      }
    }
  }

  passOn();
  if (typeof DecompressionStream === "undefined") return;
  inflate(bytesOf(payload)).then(function (text) {
    var outing = JSON.parse(text);
    if (outing && typeof outing === "object") show(outing);
  }).catch(function () { /* Not an outing this page can read: the canyon and the buttons stay. */ });
})();
