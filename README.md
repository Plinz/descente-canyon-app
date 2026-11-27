# Descente-Canyon (application Android)

Application Android **non officielle** pour consulter les fiches canyons de
[descente-canyon.com](https://www.descente-canyon.com), y compris **hors ligne**, signaler des débits et
estimer le débit probable d'un canyon pour les trois prochains jours.

- Catalogue complet embarqué dans l'APK (fiches, points GPS, bibliographie, réglementation, bassins versants) :
  la consultation ne dépend pas du réseau.
- Débits, photos, forum et notes d'intérêt lus sur le site quand le réseau est disponible ; les débits saisis hors
  ligne sont envoyés automatiquement au retour du réseau (file consultable, notification à l'envoi). Un débit n'existe
  que sur le site : l'envoi passe toujours par une relecture, et le carnet de sorties ne garde ni niveau ni commentaire
  de débit, il lit le message publié (copie en lecture seule de « Ma liste de courses », oubliée à la déconnexion).
- Données personnelles locales : favoris avec leur dernier débit connu, carnet de sorties, canyons consultés récemment, notes
  personnelles par canyon, nom et e-mail du dernier signalement.
- Estimation du débit par un modèle exécuté sur l'appareil (moyenne d'un CatBoost et d'un petit réseau de neurones ;
  météo Open-Meteo, pluviomètres Météo-France et MeteoSwiss, état hydrologique simulé), affichée en fourchette quand le
  modèle hésite entre deux niveaux. Voir [docs/debit-prediction-model.md](docs/debit-prediction-model.md).
- Fiche canyon orientée décision : météo en une carte (alerte orage, pluie tombée sur 72 h et prévue sur 48 h avec
  graphique heure par heure et heures d'orage, 6 prochains jours, lever et coucher du soleil), canyons à moins de
  30 km avec leur dernier débit connu (plan B), répartition des débits signalés selon le mois. Les dernières
  prévisions et estimations téléchargées restent affichées hors ligne.
- Préparation de sortie : horaires estimés d'après les temps de la fiche, à l'heure locale du canyon, marge avant le
  coucher du soleil (calculé sur l'appareil), conditions du jour (froid, hors saison d'après les mois des débits
  signalés), message à envoyer à un proche (lieu, horaires, heure d'alerte, numéro d'urgence, coordonnées des
  parkings) et rappel au retour pour signaler le débit observé. Les rappels de la sortie (retour, alerte proche) sont
  des alarmes, à la minute avec l'autorisation « Alarmes et rappels ».
- Carnet de sorties personnel (« Mes sorties », depuis les favoris) : sorties ajoutées depuis la fiche ou avec « Je
  suis rentré », statistiques ; il reste sur le téléphone.
- Écran d'urgence hors ligne, à un geste : bouton SOS de l'accueil, de la fiche et de la carte, raccourci « Urgence »
  de l'icône, notification « Sortie en cours » ouvrable écran verrouillé (le 112 s'appelle alors sans déverrouiller).
  Il nomme le canyon tout seul (fiche ouverte, sortie en cours ou position GPS, avec choix d'un canyon voisin),
  donne les numéros du pays (une centaine de pays, sinon ceux du réseau mobile), la position à lire ou à envoyer par
  SMS avec le point de la fiche le plus proche, les points triés par distance et direction, et l'engagement de la
  fiche.

> Le site ne propose pas d'API : l'application lit ses pages HTML. Toute évolution de leur structure peut casser une
> fonctionnalité ; l'erreur `SiteFormatChanged` le signale explicitement à l'utilisateur.

## Architecture

Projet Gradle multi-modules, en couches :

```
app                      Activity, navigation (5 onglets), onboarding, démarrage, synchronisations (WorkManager), alarmes de sortie, notifications
feature/home             Accueil : derniers débits, forum, premiers pas et nouveautés ; écran de bienvenue
feature/explore          Explorer : recherche et carte réunies (mêmes critères), aussi pour choisir le canyon d'une sortie ou d'un jour de séjour
feature/canyon           Fiche canyon (onglets Conditions, Topo, Photos, Débits ; barre d'actions), galerie, prédiction de débit
feature/contribute       Formulaires de débit et de note d'intérêt, débits en attente d'envoi
feature/favorites        Favoris
feature/notifications    Centre de notifications (suivis)
feature/users            Membres du site : recherche, profil, suivi de leurs messages du forum
feature/settings         Réglages (thème, langue, notifications), sources des données, licences, confidentialité
feature/outing           Onglet Sorties (sorties, séjours, carnet en bref), préparation de sortie, sortie en cours, écran d'urgence
feature/account          Onglet Moi (compte, suivis, contributions, mémos, données, accès aux réglages) et carnet : sorties, canyons, bilan, import de la liste de courses du site
core/ui                  Design system, composants partagés, widget carte, ressources (chaînes en 6 langues)
core/data                Bases Room, réseau (OkHttp + Jsoup), scraping, repositories, modèle de débit
core/domain              Modèles, interfaces des repositories, cas d'usage, erreurs typées (Kotlin pur)
core/testing             Outils de test partagés
build-logic              Plugins de convention Gradle (descente.android.*, descente.jvm.library)
server                   Serveur léger de l'application (Python, hors build Android) : catalogue signé, traces GPS partagées
```

Règles de dépendance : une fonctionnalité ne dépend que de `core:domain` et `core:ui`, jamais de `core:data` ni
d'une autre fonctionnalité ; seul `app` assemble le tout (Hilt).

### Données locales

| Base | Contenu | Cycle de vie |
| --- | --- | --- |
| `catalog.db` (`CatalogDatabase`) | Référentiel des canyons, généré au build | Copiée depuis l'APK, **remplacée en bloc** à chaque installation ou mise à jour (`CatalogInstaller`), ou par un catalogue plus récent téléchargé du serveur de l'application (au démarrage suivant) : aucune migration |
| `user.db` (`UserDatabase`) | Favoris, débits en attente d'envoi, canyons récents, notes personnelles, caches (débits, photos, météo) | Conservée ; toute évolution du schéma exige une migration testée (`UserDatabaseMigrationTest`) |

Les installations antérieures (base unique `descente_canyon_db`, versions ≤ 2.2) sont migrées une fois au démarrage
par `LegacyDatabaseMigrator`. La sauvegarde Android (`data_extraction_rules.xml`) exclut le catalogue et le modèle,
reconstruits depuis l'APK : sans cela, le quota de 25 Mo serait dépassé et aucune donnée ne serait sauvegardée.
Une fiche dont le contenu a plus de 30 jours est rafraîchie depuis le site quand le réseau est disponible.

### Réseau et erreurs

- Une seule pile HTTP : un `OkHttpClient` partagé par le site, les API JSON, Coil et MapLibre. Les cookies du site ne
  sont acceptés et envoyés qu'en HTTPS vers descente-canyon.com (`SiteCookieJar`).
- Les échecs sont des `AppError` typées (`Offline`, `SessionExpired`, `Rejected`, `SiteFormatChanged`…), traduites en
  messages par l'interface (`UiText`) ; aucun message d'exception brut n'est affiché.
- Un envoi (débit, note) n'est considéré réussi que si le site le confirme ou si la donnée est relue sur le site.

## Serveur de l'application (optionnel)

Le dossier `server/` contient un backend léger ([server/README.md](server/README.md)) : chaque nuit, il relit
une partie des fiches du site avec le scraper du dépôt, reconstruit le catalogue avec le même générateur que
l'APK et le publie, signé, s'il a changé ; il héberge aussi les traces GPS partagées depuis l'app, classées par
les votes, sans modération. Le site reste la seule destination des débits, topos et messages : le serveur ne
voit jamais d'identifiant ni de cookie du site.

L'app n'appelle ce serveur que si le build connaît son adresse HTTPS et sa clé publique de signature :

```properties
# ~/.gradle/gradle.properties ou local.properties (ou DC_BACKEND_URL / DC_BACKEND_PUBLIC_KEYS)
dc.backendUrl=https://api.example.org
dc.backendPublicKey=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...
```

Sans ces valeurs (cas par défaut), rien ne change : catalogue de l'APK, aucune section de traces. Le workflow de
release lit les variables de dépôt `DC_BACKEND_URL` et `DC_BACKEND_PUBLIC_KEYS`. Avec elles, l'app lit le
manifeste signé deux fois par jour, télécharge un catalogue plus récent (Wi-Fi par défaut), vérifie empreinte,
schéma Room et intégrité, et l'installe au démarrage suivant ; tout échec la laisse sur ses propres données.

## Prérequis

- JDK 25 (le démon Gradle le demande via `gradle/gradle-daemon-jvm.properties`)
- Android SDK avec la plateforme 37 (compileSdk ; l'application cible Android 16, API 36)
- Python 3 dans le `PATH` (`python3` ou `python`, sinon `-PpythonBinary=...`) : le build génère le catalogue embarqué
  et les index du modèle à partir de `offline-data/` et `modele_statistique/`

## Commandes courantes

```bash
./gradlew :app:assembleDebug                         # APK de debug
./gradlew testDebugUnitTest :core:domain:test        # tests unitaires (JVM, Robolectric pour les bases)
./gradlew lintDebug                                  # Android Lint, tous modules
./gradlew spotlessApply                              # formatage (ktlint, règles dans .editorconfig)
./gradlew spotlessCheck                              # vérification du formatage (CI)
./gradlew :app:connectedDebugAndroidTest             # tests instrumentés (appareil ou émulateur)
scripts/ci/smoke-launch.sh                           # démarrage à froid de l'APK de debug réel (appareil ou émulateur)
./gradlew :app:reportReleasePackageSizes             # taille des artefacts de release
```

Hook Git (mêmes vérifications que la CI avant chaque push) :

```bash
git config core.hooksPath .githooks
```

## Intégration continue et release

- `.github/workflows/ci.yml` : formatage, tests unitaires, lint, build debug et APK de tests à chaque PR et sur
  `main`, puis tests instrumentés et test de démarrage de l'app réelle sur émulateur.
- `.github/workflows/release.yml` : release manuelle (incrément de version, AAB et APK signés, tag, GitHub Release).
  Voir [docs/github-release-pipeline.md](docs/github-release-pipeline.md).
- Dependabot propose chaque semaine les mises à jour des dépendances Gradle et des actions GitHub.

## Données et modèles

| Dossier | Contenu |
| --- | --- |
| `offline-data/full/room-import/` | Export JSON du catalogue, source du `catalog.db` embarqué |
| `data-overlay/`, `tools/overlay-merge/` | Canyons et enrichissements propres à l'application ([docs/overlay-architecture.md](docs/overlay-architecture.md)) |
| `data-overlay/canyon-chains.json` | Enchaînements d'un canyon au suivant (à la suite, à pied avec le temps du topo, interdit dans le lit), relus à la main sur tout le catalogue ; après un nouveau scrap, `scripts/app/canyon_chain_candidates.py` liste les paires restant à relire |
| `modele_statistique/` | Modèle de débit exporté (arbres CatBoost `*.trees`, réseaux `*.mlp`, seuils, tables de features) |
| `scripts/debit_model/` | Pipeline d'entraînement du modèle de débit ([README](scripts/debit_model/README.md)) |
| `scripts/watersheds/`, `watershed-results/` | Calcul des bassins versants ([README](scripts/watersheds/README.md)) |
| `scripts/app/` | Générateurs du catalogue embarqué et des index du modèle, appelés par le build |

Le schéma du catalogue est exporté par Room dans `core/data/schemas/` ; le générateur
`scripts/app/generate_prepackaged_room_db.py` s'en sert, et le test `CatalogAssetTest` vérifie que Room accepte la
base produite.

Les membres (`forum_users.json`) viennent de `scripts/app/extract_forum_users.py`, qui lit les débits produits par
`scripts/debits/build_observation_dataset.py` et quelques pages publiques du forum. Seuls les comptes du site y
figurent : un nom saisi sans compte sous un débit (« Pierre, Paul et Jacques », champ « groupe » du formulaire
anonyme) n'est pas un membre. Le site les distingue dans la remarque d'un débit : compteurs pour un membre,
« Utilisateur non enregistré » sinon.
