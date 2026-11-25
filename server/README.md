# Serveur de l'application

Backend léger de l'application Android. Il ne remplace pas descente-canyon.com : débits, topos, notes et
forum restent lus et publiés sur le site, depuis le téléphone, avec le compte de la personne. Le serveur
ne voit jamais un identifiant ni un cookie du site.

Il fait trois choses :

1. **Publier des fichiers signés** que l'app télécharge : un manifeste (`/v1/manifest.json`) et le
   catalogue. Chaque nuit, une tâche relit une partie des fiches du site (chaque fiche une fois tous les
   `DC_ROTATION_DAYS` jours) avec le scraper du dépôt, reconstruit `catalog.db` avec le même générateur
   que le build de l'APK, le vérifie et ne le publie que s'il a changé. Elle publie aussi un index
   (empreinte de chaque canyon) et un petit paquet par canyon : l'app ne télécharge que les canyons
   modifiés et les applique tout de suite dans sa base, sans redémarrer (un canyon interdit apparaît
   aussitôt). Le catalogue entier reste publié pour une app trop en retard. Sans serveur, ou si le
   serveur se tait, l'app garde le catalogue de son APK.
2. **Calculer les estimations de débit de tous les canyons**, trois fois par jour, avec le modèle, les
   données et le calcul de l'app (voir « Estimations de débit »). L'app les affiche d'emblée (listes,
   carte, fiche) et ne calcule plus elle-même que ce que le fichier n'a pas.
3. **Héberger les traces GPS partagées** par les personnes qui utilisent l'app, classées par leurs votes,
   sans modération : des contrôles automatiques refusent ce qui ne peut pas être une trace du canyon, et
   les votes mettent en avant ou écartent le reste (voir « Traces et votes »).

L'app reste entièrement utilisable sans le serveur.

## Architecture

```
descente-canyon.com ──► tâche catalogue (timer systemd, 02:15 UTC) ──► /srv/dc/publish/v1 (fichiers signés)
Open-Meteo, Météo-France,                                                 ▲
MeteoSwiss, débits du site ──► tâche estimations (06:40, 12:40, 18:40 UTC)┘
                                                                         │
App ◄── Caddy (HTTPS automatique, journaux masqués) ◄────────────────────┘
App ──► Caddy ──► API (FastAPI) ──► SQLite (traces, votes, quotas)
restic ──► sauvegarde chiffrée chez un autre fournisseur        healthchecks.io ◄── fin de chaque tâche
```

| Chemin | Rôle |
| --- | --- |
| `dcserver/` | Code Python : manifeste et signature, publication atomique, API, traces, tâche catalogue, CLI |
| `tests/` | Tests (`pytest`), dont un test de bout en bout de la tâche catalogue sur les données du dépôt |
| `Dockerfile` | Image commune à l'API et aux tâches |
| `deploy/` | Compose, Caddyfile, timers systemd, script d'installation du VPS, sauvegarde |

### Contrat avec l'app

- `GET /v1/manifest.json` : enveloppe `{format, keyId, payload, signature}`. `payload` est le JSON du
  manifeste en base64 (schéma dans `dcserver/manifest.py`), `signature` sa signature ECDSA P-256
  (SHA256withECDSA, DER, base64). L'app refuse un manifeste mal signé ou dont la `sequence` recule.
- `GET /v1/files/<sha256>.db.gz` : catalogue compressé (gzip), immuable. L'app vérifie le SHA-256 du
  fichier, la version et l'empreinte Room du schéma, l'intégrité SQLite, puis l'installe au démarrage
  suivant.
- `GET /v1/files/<sha256>.json.gz` (entrée `catalogIndex`) : index du catalogue, l'empreinte et la date
  de lecture de chaque canyon ; `GET /v1/catalog/bundles/<empreinte>.json.gz` : les lignes d'un canyon
  (`scripts/app/catalog_bundles.py`, l'empreinte est celle du contenu). L'app télécharge les paquets qui
  diffèrent des siens (400 au plus, sinon le catalogue entier) et les applique en une transaction.
- `GET /v1/files/<sha256>.json.gz` (entrée `predictions`) : estimations de débit de tous les canyons
  (schéma 1, `dcserver/predictions/job.py`), vérifiées par leur SHA-256 comme le reste.
- Traces : `GET /v1/canyons/{id}/tracks`, `POST /v1/canyons/{id}/tracks?role=APPROACH|RETURN|ESCAPE|FULL`
  (corps : le fichier GPX, 2 Mo au plus), `PUT /v1/tracks/{id}/vote` (`{"value": 1|0|-1}`),
  `DELETE /v1/tracks/{id}` (l'auteur seulement). Les écritures portent l'en-tête `X-Install-Key` : une clé
  aléatoire de l'installation, envoyée uniquement avec les contributions, jamais avec les lectures.
  Erreurs : `{"error": CODE}` (`TOO_FAR`, `DUPLICATE`, `RATE_LIMITED`…, voir `dcserver/tracks/service.py`).

### Traces et votes

- À l'envoi, le serveur lit le GPX en refusant DTD et entités, garde la géométrie et l'altitude, jette
  les horaires (seul le mois de la sortie reste), simplifie le tracé (4 m), calcule longueur et
  dénivelés. Il refuse une trace qui ne passe pas à moins de 3 km d'un point GPS du canyon, qui s'en
  éloigne de plus de 15 km, de moins de 100 m ou de plus de 40 km, ou déjà envoyée.
- Pas de texte libre (ni nom ni commentaire) : rien à modérer. Le rôle (approche, retour, échappatoire,
  sortie complète) se choisit dans une liste.
- Une voix par installation et par trace (+1 fiable, −1 fausse ou dangereuse), modifiable. On ne vote
  pas pour ses propres traces. Un même réseau (IPv4 /24, IPv6 /48) pèse au plus 2 voix de chaque signe
  sur une trace : réinstaller l'app pour voter plusieurs fois ne suffit pas.
- Statut : **approuvée** (≥ 3 pour et trois fois plus de pour que de contre ; la meilleure de chaque rôle
  est la trace de référence), **écartée** (≥ 3 contre et deux fois plus de contre que de pour : masquée par
  défaut), **contestée** (au moins un contre), sinon **nouvelle**. Les seuils sont dans
  `dcserver/tracks/standing.py`.
- Quotas par jour : 20 essais et 5 traces par installation (60 et 40 par réseau), 150 votes par
  installation (400 par réseau).
- L'auteur supprime sa trace depuis l'app. Seule action manuelle prévue : le retrait d'un contenu
  illicite signalé (obligation d'hébergeur), avec `dcserver track delete <id>`.

## Développement

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -e ".[test]"
.venv/bin/python -m pytest -m "not slow"   # rapide
.venv/bin/python -m pytest -m slow         # tâche catalogue de bout en bout (~20 s)
```

API en local (l'émulateur Android joint la machine hôte en `10.0.2.2`) :

```bash
export DC_PEPPER=$(openssl rand -hex 32) DC_DATA_DIR=build/data DC_PUBLISH_DIR=build/publish DC_TRUSTED_PROXY=false
.venv/bin/dcserver canyon-reference --catalog-db ../app/build/generated/runtime-assets/main/databases/catalog.db
.venv/bin/dcserver api --host 0.0.0.0 --port 8080
```

### Estimations de débit

La tâche `predictions` reprend tel quel le calcul de l'app : `scripts/debit_model/runtime_reference.py`
(la référence Python du calcul Kotlin, vérifiée par les vecteurs de test communs), les fichiers du
modèle de `modele_statistique/` et les mêmes sources. Un modèle réentraîné et poussé sur le dépôt sert
dès la tâche suivante, sans nouvelle version de l'app. À chaque passage :

- **Débits signalés** : la grille « derniers débits » du site (une page), gardée jour par jour, et la
  page de débits des canyons qui ont un nouveau signalement, plus 200 pages jamais lues
  (`DC_DEBIT_PAGES_PER_RUN`) pour rattraper les débits signalés depuis la construction du modèle.
- **Météo** : Open-Meteo répond pour un point avec les valeurs de sa maille de 0,25° (la même pour
  ERA5 et IFS), la température corrigée de l'altitude du point (0,65 °C / 100 m). Chaque canyon est
  donc situé une fois (maille, altitude, fuseau), puis l'historique de 400 jours et les prévisions sont
  lus par maille : environ 630 mailles pour 4 100 canyons. Les appels sont comptés comme Open-Meteo
  les compte, espacés sous la limite horaire et arrêtés au budget du jour (`DC_OPENMETEO_DAILY_CALLS`,
  9 000 sur les 10 000 du plan gratuit) : environ 3 000 par jour en régime établi ; les premiers jours
  remplissent les historiques (environ 3 jours pour tout couvrir, les canyons les plus signalés d'abord).
- **Pluviomètres** : fichiers quotidiens et horaires de Météo-France (48 départements) et stations
  MeteoSwiss, lus une fois par jour pour tous les canyons.
- **Calcul** de J, J+1 et J+2 pour chaque canyon dont l'historique météo est complet (environ 7 min
  pour 4 100 canyons, 150 Mo de mémoire), puis publication.
- **Vérification en direct** : la première estimation de chaque canyon pour chaque jour est gardée et
  comparée aux débits signalés ensuite (90 derniers jours) ; le résultat est publié avec les estimations
  et l'app l'affiche dans « Comprendre l'estimation ».

L'app prend l'estimation du serveur tant qu'elle est celle du jour (moins de 14 h) et qu'aucun débit
signalé sur la fiche n'est plus récent que ceux que le serveur connaissait ; sinon, ou sans serveur,
elle calcule comme avant. Interrupteur : `manifest feature serverPredictions off`.

## Mise en service

À faire une fois, quand le VPS (Debian 13) et le nom de domaine sont pris.

1. **DNS** : un enregistrement `A` (et `AAAA` si le VPS a une IPv6) du sous-domaine choisi vers le VPS.
2. **Installation** (en root sur le VPS) : copier `deploy/bootstrap.sh` puis
   `bash bootstrap.sh "$(cat ~/.ssh/id_ed25519.pub)"` (depuis votre PC, la clé publique SSH). Le script met à
   jour le système, crée le compte `dc` (seul autorisé en SSH, par clé), installe Docker, le pare-feu
   (22, 80, 443), les mises à jour de sécurité automatiques, un swap de 2 Go, clone le dépôt dans
   `/srv/dc/repo` et copie les timers. Activer aussi le pare-feu du fournisseur avec les mêmes ports.
3. **Configuration** (en `dc`) : `cp /srv/dc/repo/server/deploy/env.example /srv/dc/env`, `chmod 600`,
   renseigner `DC_PEPPER` (`openssl rand -hex 32`) ; `cp .env.example .env` dans `server/deploy` avec le
   domaine et l'e-mail pour Let's Encrypt.
4. **Clé de signature**, sur votre PC, jamais dans git :
   ```bash
   openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out manifest-key.pem
   openssl pkey -in manifest-key.pem -pubout -outform DER | base64 -w0   # clé publique pour l'app
   ```
   Gardez une copie de `manifest-key.pem` hors du serveur (gestionnaire de mots de passe) : sans elle,
   les apps installées n'accepteraient plus aucun manifeste. La copier dans `/srv/dc/secrets/` (`chmod 600`).
5. **Démarrage** : `cd /srv/dc/repo/server/deploy && docker compose up -d --build`, puis une première
   tâche : `docker compose run --rm --no-deps jobs job catalog`. Vérifier
   `curl https://<domaine>/v1/health` et `curl https://<domaine>/v1/manifest.json`.
6. **Timers** : `sudo systemctl enable --now dc-catalog.timer dc-predictions.timer dc-backup.timer` (après
   l'étape 7 pour le dernier). La tâche des estimations attend le premier catalogue (`data/catalog/current.db`).
7. **Sauvegarde** : créer un dépôt restic chez un autre fournisseur (Storage Box en SFTP, ou un bucket
   S3), `cp backup.env.example /srv/dc/backup.env`, le mot de passe restic dans
   `/srv/dc/secrets/restic-password` (et une copie ailleurs), puis `restic init`. Tester une restauration.
8. **Supervision** : trois contrôles sur healthchecks.io (tâche catalogue, `DC_HEALTHCHECKS_URL` ; tâche
   des estimations, `DC_HEALTHCHECKS_PREDICTIONS_URL`, période 6 h ; sauvegarde) et une sonde HTTP
   externe sur `/v1/health`.
9. **App** : dans `~/.gradle/gradle.properties` (ou les secrets du workflow de release),
   `dc.backendUrl=https://<domaine>` et `dc.backendPublicKey=<clé publique base64>`. Sans ces deux valeurs,
   l'app n'appelle aucun serveur. Plusieurs clés publiques peuvent être données, séparées par des
   virgules, pour changer de clé sans bloquer les apps installées.

## Exploitation

- Messages et interrupteurs, signés à la volée :
  `docker compose run --rm --no-deps jobs manifest message-add --id site-2026-12 --level warning --fr "…" --en "…"`,
  `… manifest message-remove --id …`, `… manifest feature communityTracks off`, `… manifest min-version 60`,
  `… manifest show`.
- Rapports de la tâche catalogue : `/srv/dc/data/reports/catalog-*.json` (fiches relues, modifiées,
  échecs). Elle ne publie rien si plus de 5 % des pages n'ont plus de nom de canyon (format du site
  changé), si plus de 30 % des fiches n'ont pas pu être lues, ou si le catalogue rétrécit (canyons
  −2 %, points −3 %, réglementations −5 %) ; healthchecks.io prévient alors par e-mail.
- Rapports de la tâche des estimations : `/srv/dc/data/reports/predictions-*.json` (canyons estimés,
  appels Open-Meteo du jour, mailles complètes, pages de débits lues, pluviomètres, vérification en direct).
  Lancer à la main : `docker compose run --rm --no-deps jobs job predictions` (`--dry-run` pour ne rien publier).
- **Mettre à jour le serveur**, depuis votre PC, une seule commande :
  ```bash
  ssh -t dc@<domaine> /srv/dc/repo/server/deploy/update.sh
  ```
  Le script (`deploy/update.sh`) récupère la branche main, reconstruit l'image et redémarre l'API si le
  code du serveur a changé, recharge Caddy si sa configuration a changé, installe les unités systemd
  nouvelles ou modifiées, active les timers (la sauvegarde dès que `/srv/dc/backup.env` existe), lance
  en arrière-plan les tâches qui n'ont jamais tourné, puis vérifie `/v1/health`. Le relancer ne change
  rien. Le mot de passe sudo de `dc` n'est demandé que pour toucher à systemd. Options : `--rebuild`
  (reconstruire l'image quand même), `--no-jobs` (laisser les tâches attendre leur timer). La toute
  première fois, le script n'est pas encore sur le serveur : `git -C /srv/dc/repo pull` avant.
- Journaux : Caddy garde 7 jours d'accès, adresses tronquées (IPv4 /24, IPv6 /48), sans la clé
  d'installation. L'API ne journalise pas les requêtes. La base garde, pour les traces et les votes, un
  HMAC de la clé d'installation et du réseau (avec `DC_PEPPER`, jamais stocké avec eux).
