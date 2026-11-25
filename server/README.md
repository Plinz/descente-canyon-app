# Serveur de l'application

Backend léger de l'application Android. Il ne remplace pas descente-canyon.com : débits, topos, notes et
forum restent lus et publiés sur le site, depuis le téléphone, avec le compte de la personne. Le serveur
ne voit jamais un identifiant ni un cookie du site.

Il fait deux choses :

1. **Publier des fichiers signés** que l'app télécharge : un manifeste (`/v1/manifest.json`) et le
   catalogue (`/v1/files/<sha256>.db.gz`). Chaque nuit, une tâche relit une partie des fiches du site
   (chaque fiche une fois tous les `DC_ROTATION_DAYS` jours) avec le scraper du dépôt, reconstruit
   `catalog.db` avec le même générateur que le build de l'APK, le vérifie et ne le publie que s'il a
   changé. Sans serveur, ou si le serveur se tait, l'app garde le catalogue de son APK.
2. **Héberger les traces GPS partagées** par les personnes qui utilisent l'app, classées par leurs votes,
   sans modération : des contrôles automatiques refusent ce qui ne peut pas être une trace du canyon, et
   les votes mettent en avant ou écartent le reste (voir « Traces et votes »).

L'app reste entièrement utilisable sans le serveur.

## Architecture

```
descente-canyon.com ──► tâche catalogue (timer systemd, 02:15 UTC) ──► /srv/dc/publish/v1 (fichiers signés)
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
- Quotas par jour : 20 essais et 5 traces par installation (60 et 15 par réseau), 150 votes par
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
6. **Timers** : `sudo systemctl enable --now dc-catalog.timer dc-backup.timer` (après l'étape 7 pour le second).
7. **Sauvegarde** : créer un dépôt restic chez un autre fournisseur (Storage Box en SFTP, ou un bucket
   S3), `cp backup.env.example /srv/dc/backup.env`, le mot de passe restic dans
   `/srv/dc/secrets/restic-password` (et une copie ailleurs), puis `restic init`. Tester une restauration.
8. **Supervision** : deux contrôles sur healthchecks.io (tâche catalogue, sauvegarde) et une sonde HTTP
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
- Mettre à jour le code du serveur : `git -C /srv/dc/repo pull` puis `docker compose up -d --build`.
- Journaux : Caddy garde 7 jours d'accès, adresses tronquées (IPv4 /24, IPv6 /48), sans la clé
  d'installation. L'API ne journalise pas les requêtes. La base garde, pour les traces et les votes, un
  HMAC de la clé d'installation et du réseau (avec `DC_PEPPER`, jamais stocké avec eux).
