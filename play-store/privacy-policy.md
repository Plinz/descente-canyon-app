## Privacy Policy

Effective date: 2026-11-19. This version replaces the one of 2026-03-27.

This policy applies to Descente Canyon App (the "Application"), a free and non-commercial Android application made by Antoine Duquennoy (the "developer"). The Application shows the canyons published on descente-canyon.com, works offline and helps prepare outings. It is an independent application: descente-canyon.com does not run it.

### In short

- The developer runs no server for the Application and receives none of your data. There is no account to create with the developer.
- The Application shows no advertising and uses no audience measurement, analytics, crash reporting or tracking service.
- What you enter in the Application stays on your phone. It also goes to your Android backup if you keep it on, and into the files you choose to export.
- To work, the Application contacts the services listed below. Each one receives what its feature needs, and your IP address.

### What stays on your phone

The Application stores on the device:

- your favourites, followed canyons and followed members, recently viewed canyons, personal notes and memos;
- your logbook (outings done, canyons, totals), including the list of your descents read from your descente-canyon.com account if you import it;
- your planned outings and stays: dates, times, group, gear list, the alert time given to a relative, chained canyons, and starting places, one of which can be your home;
- flow reports waiting to be sent, with the name and e-mail address you typed when you are not logged in, and the list of the reports you sent;
- the accident report you fill in on the emergency screen (circumstances, state of the injured person, follow-up), which the Application deletes a week after its last change;
- your settings, and caches (canyon pages, weather, flow reports, photos, drive times) for offline use;
- if you log in to descente-canyon.com, your username and the session cookies, encrypted with a key kept in the Android Keystore. Your password is never stored.

The flow estimate is computed on the phone, by a model embedded in the Application.

### Location

The location permission is optional. You can deny it or revoke it at any time in the Android settings.

When you grant it, the Application reads your location only while you use it, never in the background, and uses it on the phone to:

- list the canyons around you and sort canyons by distance;
- show your position on the maps;
- on the emergency screen, find the canyon you are in and its nearest point, and write your position into the emergency message;
- use your current position as the starting place of an outing.

Android provides the position, on most phones through Google Play services, under Google's own terms. The Application does not send your position to the developer. It only leaves the phone in these cases:

- the place search sends the centre of the map shown, which can be your position, to list nearby places first;
- the drive time calculation sends the coordinates of the starting place you chose, which can be your position or your home;
- the messages you send yourself (see below) contain your position when they include it.

### Services the Application contacts

| Service | Used for | What it receives |
|---|---|---|
| descente-canyon.com | Canyon pages, flow reports, photos and forum. In the background: new flow reports on the canyons you follow and new posts of the members you follow. | The pages requested. If you log in: your username and password, sent only to the site to open the session, then the session cookies. What you choose to publish there: flow reports, with the name and e-mail address you typed when you are not logged in, and interest ratings. If you import it, your list of descents is read from your account. |
| Open-Meteo (open-meteo.com) | Weather and flow estimate of a canyon | The coordinates of the canyon's weather point and the dates wanted, not your position. |
| Météo-France (public data hosted by OVHcloud) and MeteoSwiss (data.geo.admin.ch) | Rain gauge readings for the flow estimate | Requests for public data files. Nothing about you. |
| EDF "Ma rivière et moi" (mariviereetmoi.edf.fr) | Water releases of the dams above some canyons | The identifier of the river section. |
| Photon by komoot (photon.komoot.io) | Finding a starting place by its name | The text typed, the centre of the map shown and the language. |
| OSRM routing on the FOSSGIS servers (routing.openstreetmap.de) | Drive time from a starting place to a canyon | The coordinates of the starting place and of the canyon's car park. |
| OpenStreetMap (tile.openstreetmap.org), OpenTopoMap (tile.opentopomap.org) and MapLibre (demotiles.maplibre.org, map fonts) | Showing maps | The map tiles of the area shown. |

Every request also carries your IP address and the identifier "DescenteCanyonApp/version (Android)". No advertising identifier or device identifier is sent. All connections use HTTPS. Each service handles these data under its own privacy policy.

What you publish on descente-canyon.com is public on that site, under the name you give. The site handles it under its own rules: ask descente-canyon.com to change or remove it. Links opened from the Application open in your browser, under the rules of the site visited.

### What you send yourself

The Application prepares some content that you send with your own apps. The Application does not send it itself:

- messages to a relative about an outing (canyon, times, alert time), and the message saying you are back;
- emergency calls and messages (canyon, position, nearest point): the Application opens your phone's dialler or messaging app;
- calendar events, shared links, and the files you export: the logbook in CSV, or a JSON file with the logbook, memos, favourites, starting places, stays, planned outings and outing preferences.

A file you import is read on the phone only.

### Notifications and reminders

The notification permission is optional. Notifications tell you about new flow reports on the canyons you follow, new posts of the members you follow, reports waiting to be sent, and your outings: the "Outing under way" notification, the return reminder and the reminder before the alert time. The "Alarms & reminders" permission is optional too: it lets the outing reminders ring on time. The reminders are set again after the phone restarts. All of this is computed on the phone.

### Android backup

If Android backup is on, Android can save the Application's data (favourites, logbook, memos, outings, places, accident report, settings, reports waiting to be sent) to your Google account and restore it on a new phone, as for any application. The descente-canyon.com session, the canyon catalogue and the flow model are left out. You can turn the backup off in the Android settings.

### Keeping and deleting your data

Your data stay on the phone until you delete them: in the Application (remove favourites and follows, clear the logbook and memos in My space, delete outings and places, log out), by clearing the Application's storage in the Android settings, or by uninstalling it. The developer holds none of your data, so there is nothing to delete on the developer's side. The services listed above handle what they received, including what you published on descente-canyon.com: contact them to see or delete it.

### Children

The Application is not directed at children under 13 and does not knowingly collect data about them.

### Changes

Any change to this policy is published on this page with a new effective date.

### Contact

For any question about privacy in the Application, write to descente.canyon.app@proton.me.
