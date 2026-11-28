"""Texts of the pages of the shared links, in the app's languages, and the choice of one from the
browser's Accept-Language (French when none matches, like the app)."""
from __future__ import annotations

LANGUAGES = ("fr", "en", "de", "es", "it")
DEFAULT_LANGUAGE = "fr"

TEXTS: dict[str, dict[str, str]] = {
    "tagline": {
        "fr": "Topos, débits et sorties de canyoning, même sans réseau.",
        "en": "Canyoning topos, flows and outings, even offline.",
        "de": "Canyoning-Topos, Wasserstände und Touren, auch ohne Netz.",
        "es": "Topos, caudales y salidas de barranquismo, incluso sin cobertura.",
        "it": "Topo, portate e uscite di torrentismo, anche senza rete.",
    },
    "open_app": {
        "fr": "Ouvrir dans l'application",
        "en": "Open in the app",
        "de": "In der App öffnen",
        "es": "Abrir en la aplicación",
        "it": "Apri nell'app",
    },
    "install_app": {
        "fr": "Installer l'application",
        "en": "Install the app",
        "de": "App installieren",
        "es": "Instalar la aplicación",
        "it": "Installa l'app",
    },
    "android_app": {
        "fr": "Application Android gratuite, sans compte ni publicité.",
        "en": "Free Android app, no account, no ads.",
        "de": "Kostenlose Android-App, ohne Konto und ohne Werbung.",
        "es": "Aplicación Android gratuita, sin cuenta ni publicidad.",
        "it": "App Android gratuita, senza account né pubblicità.",
    },
    "after_install": {
        "fr": "Une fois l'application installée, ce lien s'y ouvre directement.",
        "en": "Once the app is installed, this link opens in it.",
        "de": "Ist die App installiert, öffnet sich dieser Link direkt darin.",
        "es": "Con la aplicación instalada, este enlace se abre directamente en ella.",
        "it": "Con l'app installata, questo link si apre direttamente al suo interno.",
    },
    "site_sheet": {
        "fr": "Fiche complète sur descente-canyon.com",
        "en": "Full sheet on descente-canyon.com",
        "de": "Vollständige Beschreibung auf descente-canyon.com",
        "es": "Ficha completa en descente-canyon.com",
        "it": "Scheda completa su descente-canyon.com",
    },
    "map": {
        "fr": "Voir sur une carte",
        "en": "View on a map",
        "de": "Auf einer Karte ansehen",
        "es": "Ver en un mapa",
        "it": "Vedi su una mappa",
    },
    "forbidden": {
        "fr": "Descente interdite",
        "en": "Descent forbidden",
        "de": "Begehung verboten",
        "es": "Descenso prohibido",
        "it": "Discesa vietata",
    },
    "canyon": {
        "fr": "Canyon",
        "en": "Canyon",
        "de": "Canyon",
        "es": "Barranco",
        "it": "Torrente",
    },
    "unknown_canyon": {
        "fr": "Ce canyon n'est pas, ou plus, dans le catalogue de l'application.",
        "en": "This canyon is not, or no longer, in the app's catalogue.",
        "de": "Dieser Canyon ist nicht oder nicht mehr im Katalog der App.",
        "es": "Este barranco no está, o ya no está, en el catálogo de la aplicación.",
        "it": "Questo torrente non è, o non è più, nel catalogo dell'app.",
    },
    "not_found_title": {
        "fr": "Lien introuvable",
        "en": "Link not found",
        "de": "Link nicht gefunden",
        "es": "Enlace no encontrado",
        "it": "Link non trovato",
    },
    "not_found_text": {
        "fr": "Ce lien n'existe pas ou a expiré.",
        "en": "This link does not exist or has expired.",
        "de": "Dieser Link existiert nicht oder ist abgelaufen.",
        "es": "Este enlace no existe o ha caducado.",
        "it": "Questo link non esiste o è scaduto.",
    },
    "shared_outing": {
        "fr": "Sortie partagée",
        "en": "Shared outing",
        "de": "Geteilte Tour",
        "es": "Salida compartida",
        "it": "Uscita condivisa",
    },
    "shared_route": {
        "fr": "Itinéraire partagé, sans date",
        "en": "Shared route, no date",
        "de": "Geteilte Route, ohne Datum",
        "es": "Itinerario compartido, sin fecha",
        "it": "Itinerario condiviso, senza data",
    },
    "shared_stay": {
        "fr": "Séjour partagé",
        "en": "Shared stay",
        "de": "Geteilter Aufenthalt",
        "es": "Estancia compartida",
        "it": "Soggiorno condiviso",
    },
    "shared_list": {
        "fr": "Liste de canyons partagée",
        "en": "Shared list of canyons",
        "de": "Geteilte Canyon-Liste",
        "es": "Lista de barrancos compartida",
        "it": "Elenco di torrenti condiviso",
    },
    "meet": {"fr": "Rendez-vous", "en": "Meeting", "de": "Treffpunkt", "es": "Encuentro", "it": "Ritrovo"},
    "meet_at_parking": {
        "fr": "{time} au parking",
        "en": "{time} at the parking",
        "de": "{time} am Parkplatz",
        "es": "{time} en el aparcamiento",
        "it": "{time} al parcheggio",
    },
    "meet_at_place": {"fr": "{time}, {place}", "en": "{time}, {place}", "de": "{time}, {place}", "es": "{time}, {place}", "it": "{time}, {place}"},
    "walk_start": {
        "fr": "Départ à pied",
        "en": "Leaving on foot",
        "de": "Abmarsch",
        "es": "Salida a pie",
        "it": "Partenza a piedi",
    },
    "back": {
        "fr": "Retour à la voiture",
        "en": "Back at the car",
        "de": "Zurück am Auto",
        "es": "Vuelta al coche",
        "it": "Ritorno all'auto",
    },
    "back_range": {
        "fr": "{time}, au plus tard {latest}",
        "en": "{time}, at the latest {latest}",
        "de": "{time}, spätestens {latest}",
        "es": "{time}, a más tardar {latest}",
        "it": "{time}, al più tardi {latest}",
    },
    "group": {"fr": "Groupe", "en": "Group", "de": "Gruppe", "es": "Grupo", "it": "Gruppo"},
    "people": {
        "fr": "{count} personnes",
        "en": "{count} people",
        "de": "{count} Personen",
        "es": "{count} personas",
        "it": "{count} persone",
    },
    "one_person": {"fr": "1 personne", "en": "1 person", "de": "1 Person", "es": "1 persona", "it": "1 persona"},
    "with_beginners": {
        "fr": "avec des débutants",
        "en": "with beginners",
        "de": "mit Anfängern",
        "es": "con principiantes",
        "it": "con principianti",
    },
    "discovery": {
        "fr": "découverte encadrée",
        "en": "guided discovery",
        "de": "geführte Schnuppertour",
        "es": "descubrimiento guiado",
        "it": "scoperta guidata",
    },
    "shuttle": {"fr": "Navette", "en": "Shuttle", "de": "Shuttle", "es": "Lanzadera", "it": "Navetta"},
    "yes": {"fr": "oui", "en": "yes", "de": "ja", "es": "sí", "it": "sì"},
    "no_date": {
        "fr": "La date se choisit dans l'application.",
        "en": "The date is chosen in the app.",
        "de": "Das Datum wird in der App gewählt.",
        "es": "La fecha se elige en la aplicación.",
        "it": "La data si sceglie nell'app.",
    },
    "stay_days": {
        "fr": "Du {start} au {end}",
        "en": "From {start} to {end}",
        "de": "Vom {start} bis {end}",
        "es": "Del {start} al {end}",
        "it": "Dal {start} al {end}",
    },
    "stay_place": {"fr": "Hébergement", "en": "Accommodation", "de": "Unterkunft", "es": "Alojamiento", "it": "Alloggio"},
    "list_count": {
        "fr": "{count} canyons",
        "en": "{count} canyons",
        "de": "{count} Canyons",
        "es": "{count} barrancos",
        "it": "{count} torrenti",
    },
    "outing_in_link": {
        "fr": "Le programme de la sortie est dans le lien : l'application le lit même sans réseau.",
        "en": "The outing's plan is in the link: the app reads it even offline.",
        "de": "Der Tourenplan steckt im Link: Die App liest ihn auch ohne Netz.",
        "es": "El programa de la salida está en el enlace: la aplicación lo lee incluso sin cobertura.",
        "it": "Il programma dell'uscita è nel link: l'app lo legge anche senza rete.",
    },
    "privacy": {
        "fr": "Confidentialité",
        "en": "Privacy",
        "de": "Datenschutz",
        "es": "Privacidad",
        "it": "Privacy",
    },
    "point": {
        "fr": "Point GPS : {coordinates}",
        "en": "GPS point: {coordinates}",
        "de": "GPS-Punkt: {coordinates}",
        "es": "Punto GPS: {coordinates}",
        "it": "Punto GPS: {coordinates}",
    },
}


def negotiate(accept_language: str | None) -> str:
    """The first language of the header the pages are written in, else French."""
    weighted: list[tuple[float, int, str]] = []
    for index, part in enumerate((accept_language or "").split(",")):
        piece = part.strip()
        if not piece:
            continue
        tag, _, params = piece.partition(";")
        quality = 1.0
        params = params.strip()
        if params.startswith("q="):
            try:
                quality = float(params[2:])
            except ValueError:
                quality = 0.0
        language = tag.strip().lower().split("-")[0]
        if language in LANGUAGES and quality > 0:
            weighted.append((-quality, index, language))
    return sorted(weighted)[0][2] if weighted else DEFAULT_LANGUAGE


def text(key: str, language: str) -> str:
    entry = TEXTS[key]
    return entry.get(language) or entry[DEFAULT_LANGUAGE]


WEEKDAYS = {
    "fr": ("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"),
    "en": ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
    "de": ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"),
    "es": ("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"),
    "it": ("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"),
}
MONTHS = {
    "fr": ("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"),
    "en": ("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
    "de": ("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember"),
    "es": ("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"),
    "it": ("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"),
}


def long_date(day, language: str) -> str:
    """"Samedi 4 juillet 2026", "Saturday 4 July 2026", "Samstag, 4. Juli 2026"..."""
    language = language if language in WEEKDAYS else DEFAULT_LANGUAGE
    weekday = WEEKDAYS[language][day.weekday()]
    month = MONTHS[language][day.month - 1]
    if language == "de":
        text_ = f"{weekday}, {day.day}. {month} {day.year}"
    elif language == "en":
        text_ = f"{weekday} {day.day} {month} {day.year}"
    elif language == "es":
        text_ = f"{weekday} {day.day} de {month} de {day.year}"
    else:
        text_ = f"{weekday} {day.day} {month} {day.year}"
    return text_[0].upper() + text_[1:]


def short_date(day, language: str) -> str:
    """"4 juillet", "4 July", "4. Juli"..."""
    language = language if language in MONTHS else DEFAULT_LANGUAGE
    month = MONTHS[language][day.month - 1]
    if language == "de":
        return f"{day.day}. {month}"
    if language == "es":
        return f"{day.day} de {month}"
    return f"{day.day} {month}"
