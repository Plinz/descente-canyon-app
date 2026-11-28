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
2. **Calculer les estimations de débit de tous les canyons**, quatre fois par jour, avec le modèle, les
   données et le calcul de l'app (voir « Estimations de débit »). L'app les affiche d'emblée (listes,
   carte, fiche) et ne calcule plus elle-même que ce que le fichier n'a pas.
3. **Héberger les traces GPS partagées** par les personnes qui utilisent l'app, classées par leurs votes,
   sans modération : des contrôles automatiques refusent ce qui ne peut pas être une trace du canyon, et
   les votes mettent en avant ou écartent le reste (voir « Traces et votes »).
4. **Recevoir les canyons proposés** depuis l'app (le site n'accepte plus de nouveau canyon) et les
   modifications de ces canyons. Rien n'est publié avant votre relecture dans l'outil d'admin ; une fiche
   validée entre dans le catalogue comme les autres et arrive sur les téléphones sans mise à jour de
   l'app (voir « Canyons proposés »).

L'app reste entièrement utilisable sans le serveur.

## Architecture

```
descente-canyon.com ──► tâche catalogue (timer systemd, 02:15 UTC) ──► /srv/dc/publish/v1 (fichiers signés)
Open-Meteo, Météo-France,                                                 ▲
MeteoSwiss, débits du site ──► tâche estimations (00:20, 06:40, 12:40, 18:40)┘
                                                                         │
App ◄── Caddy (HTTPS automatique, journaux masqués) ◄────────────────────┘
App ──► Caddy ──► API (FastAPI) ──► SQLite (traces, votes, canyons proposés, quotas)
Vous ──► tunnel SSH ──► outil d'admin (127.0.0.1:8090) ──► SQLite ──► tâche app-canyons (10 min) ──► catalogue
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
  (SHA256withECDSA, DER, base64). L'app refuse un manifeste mal signé ou dont la `sequence` recule ;
  le serveur la prend donc au moins égale à l'heure de publication en secondes Unix, pour qu'un état
  perdu ou restauré d'une sauvegarde plus ancienne publie encore un manifeste que les téléphones acceptent.
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
- Canyons proposés : `POST /v1/canyon-proposals` (`{"kind": "NEW"|"EDIT", "canyonId", "content", "licence":
  "CC0-1.0"}`, 256 Ko au plus), `GET /v1/canyon-proposals/mine` (les propositions de l'installation et la
  décision prise), `DELETE /v1/canyon-proposals/{id}` (retirer une proposition en attente), avec
  `X-Install-Key`. `content` reprend les champs d'une fiche du site (`dcserver/canyons/content.py`) ; une
  fiche refusée par les contrôles renvoie `{"error": "INVALID_<CODE>", "field": "<champ>"}`.
  Une fiche reprise d'une base sous CC BY-NC-SA (OpenCanyon, CWiki, RopeWiki) part avec `"licence":
  "CC-BY-NC-SA-4.0"` et, dans `content`, ses `sources` (`[{"name", "url", "licence", "authors"?,
  "changes"?}]`, 5 au plus) : le catalogue les affiche comme ressources web du canyon. Une modification
  faite depuis l'app ou l'outil de relecture, qui ne les portent pas, les garde.
- Boîte à idées : `POST /v1/feedback` (`{"kind": "BUG"|"IDEA"|"CHANGE", "text", "technical"?: {"appVersion",
  "versionCode", "android", "device", "language"}, "contact"?: {"username", "email"?}}`, 32 Ko au plus, texte
  de 10 à 4 000 caractères), `GET /v1/feedback/mine` (les messages de l'installation, leur statut `NEW`,
  `READ`, `PLANNED`, `DONE` ou `DECLINED` et la réponse ; jamais le contact), `DELETE /v1/feedback/{id}`
  (l'auteur l'efface du serveur), avec `X-Install-Key`. Refus : `{"error": "TEXT_TOO_SHORT", "field":
  "text"}`, `BAD_EMAIL`, `RATE_LIMITED`, `TOO_MANY_OPEN`, `BLOCKED` (`dcserver/feedback/service.py`).

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

### Canyons proposés

- **Qui propose quoi.** N'importe qui, depuis l'app (Mon espace › Proposer un canyon), propose un nouveau
  canyon ; sur la page d'un canyon ajouté dans l'app, « Proposer une modification ». Les fiches du site ne
  se modifient pas ici : le site reste leur source. Les propositions sont anonymes (HMAC de la clé
  d'installation et du réseau, comme les traces) et placées sous CC0, comme les traces.
- **Contrôles automatiques** (`dcserver/canyons/content.py`) : mêmes champs et mêmes formats que le site
  (cotation `v4a3III`, durées `1h30` / `45min` / `néant` en 10 caractères, navette `néant` / `5.0km`).
  Obligatoires : le nom, un point GPS de n'importe quel type (parking, entrée, sortie…) et au moins un
  texte (descente, approche, remarques…). Points à moins de 30 km les uns des autres, textes bornés,
  caractères de contrôle retirés. Quotas : 10 propositions par jour et par installation (30 par
  réseau), 20 en attente au plus.
- **Lieu déduit du point.** Pays, région, département, massif et bassin laissés vides sont repris du
  canyon du catalogue le plus proche du point qui place le canyon (l'entrée, sinon le premier point), à
  moins de 25 km (`canyon-reference.json`, réécrit par chaque catalogue). La commune ne l'est pas : le
  canyon voisin est souvent dans la commune d'à côté. Une fiche ne se publie qu'avec son pays (les
  filtres de zone de l'app en dépendent) : loin de tout canyon, vous l'écrivez avant de publier.
- **Rien n'est publié sans vous.** L'outil d'admin liste les propositions, montre la fiche (corrigeable
  avant publication), la carte avec les canyons du catalogue à moins de 5 km (en rouge les canyons
  interdits, souvent des fiches vidées exprès par le site), les autres propositions proches, les
  changements d'une modification champ par champ, et ce que l'auteur a déjà proposé. Vous publiez,
  refusez (motif dans une liste et note facultative, que l'auteur voit dans l'app) ou bloquez l'auteur.
- **Historique.** Chaque version publiée d'une fiche est gardée ; « Revenir à cette version » republie
  une ancienne version comme nouvelle version. Une fiche se masque (elle quitte le catalogue et les
  téléphones, son numéro reste pris) et se réaffiche. Vous pouvez aussi ajouter une fiche directement.
- **Publication.** Les canyons ajoutés ont des numéros à partir de 1 000 000 (le site s'arrête vers
  24 212). La tâche `app-canyons` (timer toutes les 10 min) reconstruit le catalogue sans relire le
  site dès que les fiches publiées ont changé ; les téléphones les reçoivent à leur prochaine lecture du
  manifeste (12 h au plus), comme une fiche du site modifiée. Dans l'app, rien ne les distingue d'une
  fiche du site, sauf ce que le site seul fournit : photos, débits, suivi des débits, vote d'intérêt
  (grisés ou absents), pas d'estimation de débit pour l'instant, pas de lien vers le site.
- **Copie dans le dépôt.** `docker compose run -T --rm --no-deps jobs canyons export > app-canyons.json`
  écrit les fiches publiées au format de `data-overlay/app-canyons.json` : la commiter de temps en temps
  pour que l'APK les embarque (consultables hors ligne dès l'installation) et pour en garder une copie
  hors du serveur. Le catalogue du serveur prend les deux sources (la base gagne, un canyon masqué reste
  dehors) et numérote les nouveaux canyons au-dessus de la copie du dépôt.

#### Relire les canyons proposés

L'outil n'écoute que sur la boucle locale du VPS (`127.0.0.1:8090`, service `admin` du compose) : il n'est
pas exposé sur Internet. Une fois : `openssl rand -hex 24`, la valeur dans `DC_ADMIN_TOKEN` de
`/srv/dc/env`, puis `docker compose up -d admin` (ou `update.sh`). Ensuite, depuis votre PC :

```bash
ssh -N -L 8090:127.0.0.1:8090 dc@<domaine>
```

puis ouvrir http://localhost:8090 et coller le jeton (gardé par l'onglet seulement). Les onglets
« Retours » et « Retours clos » sont la boîte à idées : ouvrir un message nouveau le passe en « Lu »,
le statut et la réponse enregistrés sont ceux que l'auteur voit dans l'app (notifié au plus tard à la
lecture suivante du manifeste, deux fois par jour). Le contact (compte descente-canyon.com et e-mail) n'y
figure que si la personne, connectée dans l'app, l'a laissé joint. Un message fait ou non retenu est
effacé un an après ; « Bloquer l'auteur » vaut aussi pour ses propositions de canyons. Pour fermer les
propositions sans mise à jour de l'app (vacances, afflux de spam) : `… manifest feature canyonProposals off`
(l'app masque alors « Proposer un canyon » ; `on` pour rouvrir), de même `… manifest feature feedback off`
pour la boîte à idées (l'app ne la montre que si le manifeste l'annonce : un serveur plus ancien ne la
nomme pas). La page vérifie le nom
d'hôte (`DC_ADMIN_HOSTS`, par défaut `localhost`) pour qu'aucun autre site ouvert dans le navigateur ne
puisse l'atteindre. `docker compose run --rm --no-deps jobs canyons list` liste les canyons ajoutés et
compte les propositions en attente.

### Liens partagés

L'app partage ses propres liens, sur le domaine du serveur (`DC_DOMAIN`, `dc-app.fr`). Côté app, c'est
`dc.linkHost`, `dc-app.fr` par défaut, à côté de `dc.backendUrl` : les trois changent ensemble.
`DC_PUBLIC_URL` dans `/srv/dc/env` remplace l'adresse des liens que donne l'API, si besoin.

- `/c/<id>[-nom][/topo|conditions|photos|debits|carte]` : un canyon (sa carte, un point `?lat=&lon=`, une
  trace partagée `?trace=<id>` avec son GPX) ;
- `/s/<code>` : une sortie, un itinéraire sans date, un séjour ou une liste de favoris, gardés sous un
  code de 9 caractères (`/v1/shares`, table `shares`) jusqu'à 30 jours après leur dernier jour (un an
  pour un itinéraire ou une liste), modifiables par l'installation qui les a créés ;
- `/o/<id>#…` : une sortie entière dans le lien (QR code, ou sans serveur) : la partie après `#` n'arrive
  jamais ici, la page la lit dans le navigateur (`static/outing.js`).

Avec l'app installée, ces liens s'ouvrent directement dedans : Android lit
`/.well-known/assetlinks.json`, qui liste les certificats autorisés (`DC_ANDROID_CERT_SHA256`). Par
défaut seule la clé du workflow Release (APK de GitHub) y figure : **ajouter la clé de signature
d'application de Google Play** (Play Console > Tester et publier > Intégrité de l'application >
Signature de l'application, empreinte SHA-256), sinon les copies installées depuis Google Play ouvrent
ces liens dans le navigateur. Sans l'app, les pages disent ce qui est partagé (en 5 langues, sans
traceur ni indexation) et mènent au Play Store, qui transmet le lien à l'app à sa première ouverture.
`… jobs share delete <code>` supprime un lien sur demande, `… jobs share prune` les liens expirés (l'API
le fait aussi au passage) ; `… manifest feature shares off` fait revenir l'app aux liens qui portent
la sortie entière.

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
  9 000 sur les 10 000 du plan gratuit) : environ 3 100 par jour en régime établi (une prévision par
  maille et par passage) ; les premiers jours remplissent les historiques (environ 3 jours pour tout
  couvrir, les canyons les plus signalés d'abord), dans ce que les passages suivants de la journée
  laissent (`DC_OPENMETEO_RESERVE`, 2 500).
- **Pluviomètres** : fichiers quotidiens et horaires de Météo-France (48 départements) et stations
  MeteoSwiss, lus une fois par jour pour tous les canyons.
- **Calcul** de J, J+1 et J+2 pour chaque canyon dont l'historique météo est complet (environ 7 min
  pour 4 100 canyons, 150 Mo de mémoire), puis publication. Les mailles déjà complètes sont mises à
  jour et publiées d'abord ; le remplissage des nouvelles mailles (jusqu'à 1 h 30 les premiers jours)
  vient ensuite, suivi d'une seconde publication qui ajoute leurs canyons. Le J du fichier est le jour
  du calcul dans le fuseau de chaque canyon.
- **Vérification en direct** : la première estimation de chaque canyon pour chaque jour est gardée et
  comparée aux débits signalés ensuite (90 derniers jours) ; le résultat est publié avec les estimations
  et l'app l'affiche dans « Comprendre l'estimation ».

L'app prend l'estimation du serveur tant qu'elle est celle du jour (moins de 14 h) et qu'aucun débit
signalé sur la fiche n'est plus récent que ceux que le serveur connaissait ; sinon, ou sans serveur,
elle calcule comme avant. Le passage de 00:20 UTC (01:20 ou 02:20 à Paris) donne l'estimation du
nouveau jour : sans lui, avant le passage de 06:40 (07:40 ou 08:40 à Paris), chaque téléphone qui
prépare sa sortie du matin la calculerait lui-même. Interrupteur : `manifest feature serverPredictions off`.

## Mise en service

À faire une fois, quand le VPS (Debian 13) et le nom de domaine sont pris.

1. **DNS** : un enregistrement `A` (et `AAAA` si le VPS a une IPv6) du domaine choisi vers le VPS.
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
6. **Timers** : `sudo systemctl enable --now dc-catalog.timer dc-predictions.timer dc-app-canyons.timer dc-backup.timer`
   (après l'étape 7 pour le dernier). La tâche des estimations attend le premier catalogue
   (`data/catalog/current.db`). Pour l'outil d'admin, voir « Relire les canyons proposés ».
7. **Sauvegarde** : créer un dépôt restic chez un autre fournisseur (Storage Box en SFTP, ou un bucket
   S3), `cp backup.env.example /srv/dc/backup.env`, le mot de passe restic dans
   `/srv/dc/secrets/restic-password` (et une copie ailleurs), puis `restic init`. Tester une restauration.
8. **Supervision** : trois contrôles sur healthchecks.io (tâche catalogue, `DC_HEALTHCHECKS_URL` ; tâche
   des estimations, `DC_HEALTHCHECKS_PREDICTIONS_URL`, période 6 h ; sauvegarde) et une sonde HTTP
   externe sur `/v1/health`.
9. **App** : dans `~/.gradle/gradle.properties` (ou les secrets du workflow de release),
   `dc.backendUrl=https://<domaine>` et `dc.backendPublicKey=<clé publique base64>`. Sans ces deux valeurs,
   l'app n'appelle aucun serveur. `dc.linkHost` (par défaut `dc-app.fr`) nomme le même domaine. Plusieurs clés publiques peuvent être données, séparées par des
   virgules, pour changer de clé sans bloquer les apps installées.

## Exploitation

- Messages et interrupteurs, signés à la volée :
  `docker compose run --rm --no-deps jobs manifest message-add --id site-2026-12 --level warning --fr "…" --en "…"`,
  `--link https://<domaine>/c/1000012` pour qu'un message ouvre un canyon ou une page (l'app ouvre ses
  propres liens elle-même), `… manifest message-remove --id …`, `… manifest feature communityTracks off`, `… manifest min-version 60`,
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
