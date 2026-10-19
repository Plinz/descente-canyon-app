# Modèle de prédiction du débit (v4)

Modèle embarqué dans l'app (`modele_statistique/`), pipeline dans `scripts/debit_model/`
(commandes : `scripts/debit_model/README.md`).

## Pourquoi une refonte

Le modèle précédent (v2) était surtout un modèle de persistance, alors que ses métriques le donnaient
pour très bon :

* son évaluation « récente » (0,81 d'exactitude) portait sur des observations **incluses dans
  l'entraînement**, avec des lookups contenant l'observation elle-même : sur un vrai holdout, il
  plafonnait à 0,67, soit le niveau de la règle naïve « répéter la dernière observation » ;
* en production, les priors massif/région « 30 derniers jours » étaient figés à la date d'export,
  `canyonDaysSinceLastObs` valait toujours 0 (époque Python `toordinal` contre Kotlin `toEpochDay`),
  et la météo de l'app (`best_match`) n'était pas celle de l'entraînement (ERA5 0,25°), avec des pluies
  extrêmes deux à quatre fois plus fréquentes ;
* le décodage par espérance du rang écrasait les extrêmes (rappel CRUE de 0,7 %) ;
* la météo ne portait aucun état hydrologique : pas de manteau neigeux, pas de stock karstique ou de
  sol, pas de mémoire au-delà de 30 jours.

## Principes du modèle v3

1. **Un état hydrologique simulé** (`hydro.py` ↔ `DebitHydroModel.kt`), sur les 400 jours précédant
   le jour cible :
   * neige sur 5 bandes d'altitude de même surface, issues de l'hypsométrie du bassin (gradient
     thermique, partition pluie/neige, fonte degré-jour) ;
   * réservoir de production GR4J (capacité tirée de la réserve utile du sol) et ETP d'Oudin ;
   * banque de réservoirs linéaires de 1, 3, 10, 30 et 90 jours (crue éclair → karst/nappe).

   Les sorties sont normalisées par la climatologie simulée propre à chaque canyon (z-scores). Le
   label est en effet relatif au canyon : un « débit correct » ne correspond pas au même volume d'un
   canyon à l'autre.
2. **Ce qui a changé depuis la dernière observation** : variation des réservoirs, apports liquides
   et évolution du manteau neigeux depuis le dernier report du canyon. Le modèle corrige ainsi la
   persistance au lieu de la répéter.
3. **Parité stricte entraînement / app** :
   * mêmes sources météo : ERA5 (`models=era5`) pour le passé, ECMWF IFS 0,25° (`ecmwf_ifs025`) pour
     les derniers jours et la prévision (même grille) ;
   * historique du canyon recalculé dans l'app à partir de la liste de débits scrapée ;
   * priors massif/région strictement causaux à l'entraînement (le *leave-one-out* fait fuiter le
     label : c'est une fonction déterministe de celui-ci) ;
   * vecteurs dorés Python → test Kotlin (`DebitFeatureBuilderGoldenTest`) sur les features, la
     sortie ONNX, la décision et le filtre de qualité des reports.
4. **Décision ordinale cumulative** : niveau = plus haut k tel que P(débit ≥ j) ≥ τ_j pour tout j ≤ k.
   Les seuils τ sont réglés hors échantillon avec un coût asymétrique : une sous-estimation d'un
   GROS ou plus coûte 2,5 fois plus. L'app affiche aussi « Risque de gros débit ou plus : x % ».
5. **Canyons froids** : 12 % des lignes d'entraînement sont dupliquées sans historique ni
   climatologie, pour que le modèle serve correctement les ~730 canyons sans aucune observation.
6. **Qualité des labels** (`label_quality.py` ↔ `DebitReportQuality.kt`) : les reports GROS / TRÈS
   GROS / CRUE non parcourus qui ne sont que des messages (risque incendie, travaux, interdiction,
   rééquipement, stationnement…) sont exclus des labels et de l'historique. Les revues manuelles de
   `observation_overrides.json` restent prioritaires.
7. **Canyons devenus interdits** : le site ne publie plus leurs reports. Leurs reports passés
   (scraping précédent) restent des labels, et leur historique est embarqué dans les lookups (l'app
   ne peut plus le scraper).

## Sources temps réel (v4)

La v3 ne voyait le canyon qu'à travers la météo et son propre historique. Or deux pratiquants qui
signalent le même canyon le même jour ne donnent le même niveau que dans 78 % des cas (99 % à ±1
niveau). Prédire un report à partir de ce que d'autres ont vu le même jour (« oracle même jour »)
plafonne donc à 79 % d'exactitude. Pour s'en approcher, il faut de l'information observée du jour,
pas seulement de la météo simulée. Toutes les sources ci-dessous ont été testées sur un banc A/B
(deux folds walk-forward, bootstrap apparié par canyon, IC 95 %).

| Source | Résultat (ΔRPS, IC 95 %) | Décision |
| --- | --- | --- |
| Reports des canyons à moins de 30 km (grille « derniers débits » du site, 16 jours) | −0,00053 [−0,00075 ; −0,00032] | retenue |
| Pluviomètres Météo-France (open data quotidien, à moins de 25 km) | −0,00085 [−0,00115 ; −0,00058] | retenue |
| + cumuls pluviomètres 14/30 jours, jours de pluie | −0,00026 [−0,00044 ; −0,00009] | retenue |
| + pluviomètres MeteoSwiss (OGD) | −0,00187 [−0,00292 ; −0,00085] sur les canyons suisses | retenue |
| Débits mesurés Hub'Eau (1 938 stations à moins de 30 km) | −0,00010 [−0,00032 ; +0,00010] | écartée (redondant avec les pluviomètres) |
| Hauteur de neige observée (postes Météo-France / Nivôse) | +0,00025 [+0,00007 ; +0,00044] | écartée (dégrade) |
| GloFAS (API Flood d'Open-Meteo) | débit simulé plat sur les petits bassins | écartée |
| ECMWF IFS 9 km historique | non testable dans les quotas gratuits Open-Meteo | piste (clé API payante) |
| Rayon des voisins 15 / 50 km, fenêtre de 15 jours | dégrade ou non significatif | 30 km, 3 et 7 jours |

Côté modèle : profondeur 8, pondération des années récentes, `rsm`, L2 plus forte : aucun gain. Un
ensemble de 3 graines gagne −0,00021 de RPS mais triple la taille embarquée : écarté.

### Ce que l'app sait vraiment, et quand

Un test de bout en bout avec les sources du jour a montré que le fichier quotidien Météo-France
publié le matin de J (05:56 UTC) s'arrête à **J-2** : la pluie de la veille (cumul de 6 h UTC J-1 à
6 h UTC J) n'y figure jamais. Entraîner avec la pluie J-1 aurait donc surestimé le gain. Or la pluie
de la veille vaut à elle seule −0,00073 de RPS [−0,00100 ; −0,00047], plus que tout le reste des
pluviomètres.

Elle est récupérée autrement :

* les **postes automatiques** Météo-France (fichier horaire du département, publié à 05:44 UTC avec
  les heures jusqu'à 03 UTC du jour) donnent la pluie de la veille sur 21 des 24 heures. L'app lit
  ce fichier (≈ 17 Mo) en flux et coupe le téléchargement dès que les stations utiles sont passées
  (lignes triées par station) ;
* les stations **MeteoSwiss** publient la veille dans leur fichier quotidien.

À l'entraînement, cette feature (`mfAuto*`) vient de la pluie quotidienne J-1 des seuls postes
automatiques (820 des 1 715 postes français proches des canyons). La convention est vérifiée : la
somme des heures 07 UTC J-1 → 06 UTC J reproduit exactement la valeur quotidienne. Avant 05:44 UTC,
la feature est absente. Pour que le modèle sache s'en passer, elle est masquée sur 20 % des lignes
d'entraînement, pour un coût négligeable.

| Source (J = aujourd'hui + h) | Connue jusqu'à | Features calculées au |
| --- | --- | --- |
| Reports des voisins (grille du site) | aujourd'hui − 1 | J − h |
| Pluviomètres, fichiers quotidiens | aujourd'hui − 2 | J − h − 1 |
| Postes automatiques (horaire FR, MeteoSwiss) | aujourd'hui − 1 | J − h |

### Deux modèles : aujourd'hui et J+1/J+2

Le matin du jour J, l'app connaît les reports et les pluviomètres jusqu'à J-1. Pour J+1 et J+2, il
lui manque 1 ou 2 jours. Un modèle unique, entraîné avec l'information complète, se dégrade quand
elle manque : à J+2, il devient moins bon que la v3. Une augmentation de données « horizon »
réparait J+1/J+2, mais coûtait significativement à J0 (+0,00035 de RPS).

L'app embarque donc deux modèles qui partagent le même contrat de features :

* `model.onnx` (J0) : information jusqu'à J-1 ;
* `model_horizon.onnx` (J+1, J+2) : reports et pluviomètres décalés de l'horizon, feature
  `infoLagDays` (entraîné sur toutes les lignes avec un décalage aléatoire de 1 ou 2 jours).

## Évaluation

Voir la section générée ci-dessous (walk-forward : un modèle par période, qui ne connaît que les
observations antérieures à cette période, comme l'app le premier jour).

Données : 166 802 observations Descente-Canyon (1988 → 07/10/2026), dont 8 canyons interdits
restaurés depuis le scraping de juin. Météo ERA5 pour 3 330 cibles.

### Walk-forward (3 périodes jamais vues, 36 343 observations)

Évaluation avec l'information réellement disponible chaque matin (tableau ci-dessus).

| Période | v3 : exactitude / MAE / RPS | v5 J0 : exactitude / MAE / RPS | v5 J+1 : exactitude / MAE / RPS |
| --- | --- | --- | --- |
| 2023 | 0,738 / 0,289 / 0,0425 | 0,736 / 0,289 / 0,0405 | 0,729 / 0,298 / 0,0417 |
| 2024 | 0,726 / 0,309 / 0,0429 | 0,739 / 0,290 / 0,0409 | 0,743 / 0,292 / 0,0420 |
| 2025 → oct. 2026 | 0,758 / 0,269 / 0,0367 | 0,777 / 0,247 / 0,0352 | 0,771 / 0,253 / 0,0358 |

Agrégé :

| | v3 | v5 J0 | v5 J0 sans postes auto (avant 05:44 UTC) | v5 J+1 | v5 J+2 | Persistance |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Exactitude | 0,745 | 0,757 | 0,755 | 0,752 | 0,752 | 0,674 |
| MAE ordinale | 0,283 | 0,267 | 0,272 | 0,275 | 0,276 | 0,416 |
| Erreurs ≥ 2 niveaux | 2,25 % | 2,06 % | — | 2,19 % | — | 6,6 % |
| GROS+ sous-estimés de ≥ 2 niveaux | 10,2 % | 9,0 % | — | 9,7 % | 9,8 % | 21,1 % |
| GROS+ : précision / rappel / F1 | 0,55 / 0,60 / 0,57 | 0,57 / 0,64 / 0,60 | F1 0,59 | F1 0,59 | F1 0,58 | 0,39 / 0,42 / 0,41 |
| RPS | 0,0398 | 0,0380 | 0,0388 | 0,0390 | 0,0392 | — |
| AUC P(≥ GROS) | 0,901 | 0,915 | — | 0,907 | 0,905 | 0,66 |

Une évaluation qui aurait supposé la pluie de J-1 connue partout (« v4 ») donnait un RPS de 0,0379 :
les postes automatiques en récupèrent la quasi-totalité. À J+1 et même J+2, la v5 reste meilleure
que la v3 le jour même.

Par pays (exactitude v3 → v5 J0) : France 0,745 → 0,759, Espagne 0,733 → 0,744, Italie 0,774 → 0,780,
Suisse 0,757 → 0,772 (F1 gros débits 0,57 → 0,62 grâce aux pluviomètres MeteoSwiss).

Comparaison au plafond : sur les 6 708 reports d'un canyon-jour signalé par au moins deux personnes,
l'« oracle même jour » atteint 0,794 d'exactitude (MAE 0,217). La v5 atteint 0,770 (MAE 0,243), la v3
0,761, la persistance 0,698 : la v5 comble 75 % de l'écart persistance → oracle (v3 : 66 %).

Résultats de la v3 :

Le gain est le plus fort quand la dernière observation est ancienne (fold 2025-2026) :

| Dernier report | Modèle : exactitude / MAE | Persistance : exactitude / MAE |
| --- | --- | --- |
| 0–7 j | 0,814 / 0,20 | 0,788 / 0,25 |
| 8–30 j | 0,747 / 0,28 | 0,675 / 0,40 |
| 31–365 j | 0,667 / 0,38 | 0,524 / 0,66 |
| > 365 j | 0,645 / 0,41 | 0,510 / 0,67 |

Canyon « froid » (sans historique ni climatologie) : exactitude 0,69 à 0,70 contre 0,64 à 0,69 pour
la climatologie du massif, et rappel GROS+ de 35 à 48 % contre 2 %.

La probabilité P(≥ GROS) est bien calibrée : chaque décile de probabilité prédite est à ±3 points de
la fréquence observée (`metrics.json`, `calibrationHigh`).

### Validation par le chemin exact de l'app

Pour vérifier la chaîne de bout en bout, le modèle entraîné jusqu'au 04/06/2026 a été rejoué sur
1 375 reports publiés du 05/06 au 12/10/2026 (56 canyons parmi les plus suivis), avec la météo
récupérée comme dans l'app, les assets exportés et ONNX (`validate_recent.py`). Résultats :
exactitude 0,835 contre 0,808 pour la persistance, MAE 0,18 contre 0,22, erreurs graves 1,0 % contre
1,6 %. Sur les 46 « gros débits » de cet été sec, 7 étaient des messages (incendie, travaux,
stationnement…), auxquels le modèle donnait à raison une probabilité quasi nulle. D'où le filtre
`label_quality.py`.

### Décision embarquée

* modèle J0 : seuils cumulatifs τ = 0,57 / 0,50 / 0,27 / 0,26 / 0,35 (SEC→FILET … TRÈS GROS→CRUE) ;
  risque « Fort » si P(≥ GROS) ≥ 0,31 (`balanced`), 0,64 (`prudent`), 0,07 (`safety_first`) ;
* modèle J+1/J+2 : τ = 0,53 / 0,51 / 0,24 / 0,28 / 0,23 ; risque « Fort » si
  P(≥ GROS) ≥ 0,26 (`balanced`), 0,66 (`prudent`), 0,07 (`safety_first`) ;
* confiance affichée : probabilité que le débit soit dans ce qui est affiché (le niveau, ou la
  fourchette quand le niveau a moins de 0,6), « élevée » à partir de 0,85, « moyenne » à partir de
  0,70. Cette probabilité est calibrée (±2 points par tranche ; canyons sans report depuis plus
  d'un an : 81 % prévus pour 78 % observés). En walk-forward, ce qui est affiché est juste 92 % du
  temps en confiance élevée (61 % des cas), 77 % en moyenne (28 %) et 64 % en faible (11 %), à
  J0 comme à J+1/J+2. L'ancien badge (écart-type de la distribution et proximité d'une limite de
  catégorie) ne séparait pas les bonnes réponses des mauvaises (AUC 0,53 contre 0,73) : près d'une
  limite, la fourchette couvre déjà les deux niveaux.

## Analyse des leviers (octobre 2026)

Question posée : qu'est-ce qui limite encore la prévision, et le jeu de données doit-il être
nettoyé ? Méthode : prédictions croisées sur tout l'historique (6 groupes d'années), classifieur du
commentaire seul (TF-IDF + régression logistique, 0,72 d'exactitude) pour auditer les labels, et
banc d'essai rapide (CatBoost lr 0,15, périodes 2024 et 2025 → oct. 2026, bootstrap apparié par
canyon). Repère : changer la seule graine aléatoire de CatBoost déplace le RPS de ±0,0002 et
l'exactitude de 0,8 point. Un écart plus petit que cela n'est pas un résultat.

### Le plafond vient des labels

Sur les 12 566 canyons-jours notés par au moins deux personnes :

| Niveau noté par l'un | L'autre note pareil | Niveau voisin le plus fréquent |
| --- | ---: | --- |
| SEC | 84 % | FILET 12 % |
| FILET | 55 % | CORRECT 41 % |
| CORRECT | 86 % | FILET 7 %, GROS 6 % |
| GROS | 52 % | CORRECT 42 % |
| TRÈS GROS | 44 % | GROS 36 % |
| CRUE | 50 % | CORRECT 19 % |

Le modèle se trompe aux mêmes frontières et dans les mêmes proportions (FILET prédit CORRECT 46 %,
GROS prédit CORRECT 38 %). FILET et GROS portent la moitié du RPS. L'essentiel de l'erreur restante
est la subjectivité de l'échelle, que ni le modèle ni un nettoyage ne peuvent supprimer.

### Où le modèle perd encore

L'information décisive est un report récent du canyon lui-même :

| Dernier report du canyon | 1 j | 2 j | 3 j | 4–7 j | 8–14 j | 15–30 j | 31–90 j | 91–365 j | > 1 an |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Exactitude v5 | 0,84 | 0,81 | 0,80 | 0,78 | 0,75 | 0,73 | 0,70 | 0,66 | 0,66 |

Les canyons sans report depuis plus de 30 jours représentent 29 % des lignes de test et 38 % de
l'erreur (le printemps est le pire cas, 0,64). En usage réel, cette part est sans doute plus forte :
on consulte aussi des canyons peu fréquentés.

Ablation (période 2025-2026, RPS 0,0354) : sans historique du canyon +0,0035, sans état
hydrologique +0,0018, sans sources temps réel +0,0016, sans descripteurs statiques +0,0011. Courbe
d'apprentissage : 25 % des lignes donnent 0,0369, 50 % 0,0361 et 100 % 0,0354, soit environ −2 % de
RPS par doublement des données.

### Nettoyage du jeu de données

* Les erreurs grossières sont rares : p(label) < 1 % pour 0,7 % des lignes. Seuls 312 reports sont
  contredits à la fois par la météo et par leur propre commentaire (liste à relire, générée
  localement : `build/debit-model/audit/suspicious_reports.csv`), dont une minorité d'erreurs réelles. Les 941
  décisions manuelles et le filtre des messages ont déjà retiré l'essentiel. Une version élargie du
  filtre ne trouve qu'une vingtaine de cas de plus.
* Retirer ou dépondérer les labels « improbables » n'améliore pas et tend à dégrader (+0,0003 à
  +0,0004 de RPS sur 2024) : ces cas portent l'incertitude réelle que le modèle doit apprendre.
* Les reports « non parcouru » (5,7 % des lignes) ont une erreur 3,6 fois plus forte, mais ils portent
  94 % des CRUE. Les garder. Retirer ceux sans commentaire n'aide pas non plus.
* Les reports saisis plus de 60 jours après la sortie (ordre des identifiants de remarque) : 1,7 %
  des lignes, +40 % d'erreur. Les retirer n'apporte rien de significatif.
* Le biais propre à chaque auteur est stable (corrélation 0,51 entre deux moitiés de ses reports),
  mais il n'explique que 2 % de la variance. Le drapeau `regulatedCatchment` ne marque que 6 canyons
  alors qu'environ 70 citent souvent un barrage ou une prise d'eau.

Testés sans gain reproductible sur les deux périodes : labels souples tirés des nuances du
commentaire (« DC- », « DC+ », « entre GD et TGD », 4 % des reports), dernier report corrigé de ces
nuances ou du biais de son auteur, biais d'auteur à l'entraînement, analogues hydrologiques (reports
passés du canyon dans un état similaire), taux de mention de barrages, reports régionaux à 30–80 km,
cumuls météo 14 à 365 jours. Le modèle exploite déjà l'information disponible.

### Réalisme de l'évaluation

* Délai de publication : environ 70–75 % des reports paraissent le jour de la sortie, 83 % le
  lendemain, 95 % sous une semaine. Le matin, l'app ne voit donc qu'une partie des reports de la
  veille. Avec ce délai simulé, J0 perd 0,2 à 0,45 point d'exactitude (RPS 0,0354 → 0,0356 en
  2025-2026). Entraîner avec ce délai n'améliore rien.
* J+1/J+2 : l'évaluation laissait voir à l'historique du canyon les reports de la veille de J,
  inconnus de l'app le matin. Corrigé, J+1 passe de 0,0361 à 0,0365 de RPS (exactitude 0,765 →
  0,761 en 2025-2026). Réentraîner avec l'historique décalé n'améliore pas.

### Indicateurs physiques testés (radar, barrages, alertes)

* **Radar COMEPHORE** (réanalyse Météo-France radar + pluviomètres, 1 km, horaire, open data depuis
  1997, publiée avec deux mois de retard) : 2004 → août 2026 extrait sur le bassin versant de 2 896
  canyons. Contrôle : corrélation journalière médiane de 0,96 avec les pluviomètres situés à moins de
  3 km. Les pluies radar des 24 h à 7 jours (cumul, intensité horaire maximale, maximum sur le bassin
  et à 5 km) ne gagnent que sur 2024 (−0,00057 de RPS) et rien sur 2025-2026 (+0,00003). Même la
  pluie réelle du jour J à 1 km, inconnue le matin (borne haute d'une prévision parfaite), n'apporte
  pas plus : sur les gros débits en France, −1 à −2 % de RPS une fois retiré le bruit d'entraînement.
  Ajouter un état hydrologique piloté par la pluie radar donne le signal le plus cohérent, mais
  faible : −1,4 % (2024) et −0,4 % (2025-2026). La pluie n'est donc pas le facteur limitant. En temps réel, seules des mosaïques
  radar 5 min sans correction par les pluviomètres sont disponibles (API Météo-France avec clé, BUFR
  ou HDF5, pas de cumul 1 h ou 24 h), ce qui imposerait un serveur.
* **Barrages** : descripteurs OpenStreetMap par bassin (barrages, seuils, conduites forcées, canaux)
  pour 3 767 canyons, dont 674 régulés (Fournel, Giffre, Ordiceto, Massaschlucht, Verdon… bien
  détectés). Gain sur 2024 (−0,00052), aucun sur 2025-2026 (+0,00012) : non retenu. Les canyons
  régulés ne sont d'ailleurs pas plus mal prédits (RPS 0,034 contre 0,036). Les débits mesurés
  Hub'Eau n'aident pas, même sur ces canyons. EDF « Ma rivière et moi » ne garde que 24 h
  d'historique (5 spots canyoning, déjà affichés dans l'app pour 4 d'entre eux) : utile en
  affichage, impossible à apprendre.
* **Vigilance Météo-France** (orages, pluie-inondation, crues ; archive depuis le 28/11/2022) : les
  jours d'alerte, la fréquence observée des gros débits égale déjà la probabilité prédite (pluie
  jaune : 22,5 % observés pour 21,8 % prédits). Rien à gagner.
* Non testables sans clé ou quota payant : humidité du sol et CAPE ERA5 (Open-Meteo gratuit trop
  limité pour 3 330 points sur 20 ans), AROME 1,3 km historique. Les hauteurs de neige des postes
  (Nivôse compris) avaient déjà été testées sans gain.

### Leviers qui changeraient vraiment la prévision

1. **Plus de reports** (produit) : un report de la veille vaut près de 19 points d'exactitude par
   rapport à un canyon sans report depuis un an, et profite aussi aux canyons voisins dans un rayon
   de 30 km. Rendre la saisie très rapide dans l'app (rappel après une sortie, niveau en un geste)
   améliore la prévision de tous.
2. **Afficher une fourchette quand le modèle hésite** : si la probabilité du niveau affiché est
   inférieure à 0,6 (22 % des cas), afficher aussi le niveau voisin le plus probable (« correct à
   gros »). Le niveau unique est alors juste 84 % du temps, la fourchette 88 %, soit 85 % au global
   contre 76 % pour un niveau unique.
3. **Radar et barrages** : testés (voir ci-dessus), gains inférieurs à 1 % et non reproductibles.
   Un serveur de traitement radar ne se justifie pas aujourd'hui.

## Limites connues et pistes

* Les orages très locaux d'été restent la principale source de gros débits manqués. Les
  pluviomètres réduisent ce problème en France et en Suisse, pas encore en Espagne ni en Italie :
  AEMET demande une clé API, et les réseaux italiens sont régionaux.
* Avant 05:44 UTC, la pluie de la veille aux postes automatiques n'est pas publiée. Le modèle s'en
  passe, au prix de +0,0008 de RPS.
* Une clé API Open-Meteo payante permettrait de tester ECMWF IFS 9 km (historique depuis 2017,
  identique en prévision), aujourd'hui hors des quotas gratuits.
* Les lâchers de barrages et les prises d'eau ne sont pas modélisés.
* Le label reste une appréciation humaine, relative au canyon et au groupe.
