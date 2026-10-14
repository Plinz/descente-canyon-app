# Descente-Canyon (application Android)

Application Android **non officielle** pour consulter les fiches canyons de
[descente-canyon.com](https://www.descente-canyon.com), y compris **hors ligne**, signaler des débits et
estimer le débit probable d'un canyon pour les trois prochains jours.

- Catalogue complet embarqué dans l'APK (fiches, points GPS, bibliographie, réglementation, bassins versants) :
  la consultation ne dépend pas du réseau.
- Débits, photos, forum et notes d'intérêt lus sur le site quand le réseau est disponible ; les débits saisis hors
  ligne sont envoyés automatiquement au retour du réseau.
- Estimation du débit par un modèle ONNX exécuté sur l'appareil (météo Open-Meteo, pluviomètres Météo-France et
  MeteoSwiss, état hydrologique simulé). Voir [docs/debit-prediction-model.md](docs/debit-prediction-model.md).

> Le site ne propose pas d'API : l'application lit ses pages HTML. Toute évolution de leur structure peut casser une
> fonctionnalité ; l'erreur `SiteFormatChanged` le signale explicitement à l'utilisateur.

## Architecture

Projet Gradle multi-modules, en couches :

```
app                      Activity, navigation, démarrage, synchronisations (WorkManager), notifications
feature/home             Accueil : derniers débits, forum
feature/explore          Recherche et carte
feature/canyon           Fiche canyon, galerie, prédiction de débit
feature/contribute       Formulaires de débit et de note d'intérêt
feature/favorites        Favoris
feature/notifications    Centre de notifications (suivis)
feature/users            Utilisateurs du forum
core/ui                  Design system, composants partagés, widget carte, ressources (chaînes en 6 langues)
core/data                Bases Room, réseau (OkHttp + Jsoup), scraping, repositories, modèle ONNX
core/domain              Modèles, interfaces des repositories, cas d'usage, erreurs typées (Kotlin pur)
core/testing             Outils de test partagés
build-logic              Plugins de convention Gradle (descente.android.*, descente.jvm.library)
```

Règles de dépendance : une fonctionnalité ne dépend que de `core:domain` et `core:ui`, jamais de `core:data` ni
d'une autre fonctionnalité ; seul `app` assemble le tout (Hilt).

### Données locales

| Base | Contenu | Cycle de vie |
| --- | --- | --- |
| `catalog.db` (`CatalogDatabase`) | Référentiel des canyons, généré au build | Copiée depuis l'APK, **remplacée en bloc** à chaque installation ou mise à jour (`CatalogInstaller`) : aucune migration |
| `user.db` (`UserDatabase`) | Favoris, débits en attente d'envoi, caches (débits, photos, météo) | Conservée ; toute évolution du schéma exige une migration testée |

Les installations antérieures (base unique `descente_canyon_db`, versions ≤ 2.2) sont migrées une fois au démarrage
par `LegacyDatabaseMigrator`. Une fiche dont le contenu a plus de 30 jours est rafraîchie depuis le site quand le
réseau est disponible.

### Réseau et erreurs

- Une seule pile HTTP : un `OkHttpClient` partagé par le site, les API JSON, Coil et MapLibre. Les cookies du site ne
  sont acceptés et envoyés qu'en HTTPS vers descente-canyon.com (`SiteCookieJar`).
- Les échecs sont des `AppError` typées (`Offline`, `SessionExpired`, `Rejected`, `SiteFormatChanged`…), traduites en
  messages par l'interface (`UiText`) ; aucun message d'exception brut n'est affiché.
- Un envoi (débit, note) n'est considéré réussi que si le site le confirme ou si la donnée est relue sur le site.

## Prérequis

- JDK 25 (le démon Gradle le demande via `gradle/gradle-daemon-jvm.properties`)
- Android SDK avec la plateforme 36
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
| `modele_statistique/` | Modèle de débit exporté (ONNX, seuils, tables de features) |
| `scripts/debit_model/` | Pipeline d'entraînement du modèle de débit ([README](scripts/debit_model/README.md)) |
| `scripts/watersheds/`, `watershed-results/` | Calcul des bassins versants ([README](scripts/watersheds/README.md)) |
| `scripts/app/` | Générateurs du catalogue embarqué et des index du modèle, appelés par le build |

Le schéma du catalogue est exporté par Room dans `core/data/schemas/` ; le générateur
`scripts/app/generate_prepackaged_room_db.py` s'en sert, et le test `CatalogAssetTest` vérifie que Room accepte la
base produite.
