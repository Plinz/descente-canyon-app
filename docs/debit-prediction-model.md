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
     sortie du modèle, la décision et le filtre de qualité des reports.
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
  les heures jusqu'à 03 ou 04 UTC du jour) donnent la pluie de la veille sur 21 ou 22 des
  24 heures. L'app lit ce fichier en entier (7 à 33 Mo selon le département) une fois par jour, et
  la table sert à tous les canyons du département. La pluie des heures manquantes est estimée par
  sa moyenne sachant les heures lues, 0,125 × √(somme lue) mm par heure manquante : ajustée sur les
  fichiers horaires 2025-2026 de l'Isère, des Alpes-Maritimes et des Hautes-Pyrénées, elle supprime
  la sous-estimation des jours de pluie (0,9 à 1,6 mm en moyenne, sur les 24 h que le modèle a
  appris) et réduit l'erreur quadratique de 5 à 11 % ; la règle de trois (× 24/21), sans biais,
  augmente l'erreur absolue de 40 % ;
* les stations **MeteoSwiss** publient la veille dans leur fichier quotidien.

À l'entraînement, cette feature (`mfAuto*`) vient de la pluie quotidienne J-1 des seuls postes
automatiques (820 des 1 715 postes français proches des canyons). La convention est vérifiée : la
somme des heures 07 UTC J-1 → 06 UTC J reproduit exactement la valeur quotidienne. Avant 05:44 UTC,
la feature est absente. Pour que le modèle sache s'en passer, elle est masquée sur 20 % des lignes
d'entraînement, pour un coût négligeable.

| Source (J = aujourd'hui + h) | Connue jusqu'à | Features calculées au |
| --- | --- | --- |
| Reports du canyon (liste du site, complétée par la grille) | aujourd'hui − 1 | J − h (depuis la v8) |
| Reports des voisins (grille du site) | aujourd'hui − 1 | J − h |
| Pluviomètres, fichiers quotidiens | aujourd'hui − 2 | J − h − 1 |
| Postes automatiques (horaire FR, MeteoSwiss) | aujourd'hui − 1 | J − h |

### Deux modèles : aujourd'hui et J+1/J+2

Le matin du jour J, l'app connaît les reports et les pluviomètres jusqu'à J-1. Pour J+1 et J+2, il
lui manque 1 ou 2 jours. Un modèle unique, entraîné avec l'information complète, se dégrade quand
elle manque : à J+2, il devient moins bon que la v3. Une augmentation de données « horizon »
réparait J+1/J+2, mais coûtait significativement à J0 (+0,00035 de RPS).

L'app embarque donc deux modèles qui partagent le même contrat de features :

* `model.trees` + `model.mlp` (J0) : information jusqu'à J-1 ;
* `model_horizon.trees` + `model_horizon.mlp` (J+1, J+2) : reports (du canyon et des voisins) et
  pluviomètres décalés de l'horizon, feature `infoLagDays` (entraîné sur toutes les lignes avec un
  décalage aléatoire de 1 ou 2 jours). Jusqu'à la v7, seuls les voisins et les pluviomètres étaient
  décalés : le modèle apprenait avec les reports du canyon jusqu'à J-1, c'est-à-dire, pour J+1,
  ceux du jour même (rarement publiés le matin) et, pour J+2, ceux du lendemain. Un report de la
  veille du jour cible est le cas de 15 % des reports de test, les mieux prédits (RPS 0,026 contre
  0,042) : l'évaluation J+1/J+2 était optimiste.

Depuis la v7, chacun est la moyenne à parts égales d'un CatBoost (`*.trees`) et d'un petit réseau
de neurones (`*.mlp`, 3 graines) entraînés sur les mêmes lignes ; voir « LSTM pour l'état
hydrologique » pour le choix.

### Exécution dans l'app (novembre 2026)

CatBoost construit des arbres symétriques : chaque niveau d'un arbre teste le même couple
(feature, seuil), donc un arbre de profondeur 7 se réduit à 7 seuils et 128 vecteurs de feuilles, et
la feuille d'une ligne est le nombre dont le bit j vaut `feature > seuil` pour le seuil j.
`tree_model.py` écrit les modèles dans ce format compact (`*.trees`), et `ObliviousTreeModel.kt` les
évalue directement en Kotlin, sans moteur d'inférence.

Jusqu'à la 2.9, l'app exécutait un export ONNX avec ONNX Runtime. Cet export déplie chaque arbre en
arbre binaire complet de 255 nœuds :

| | ONNX Runtime 1.22 | Arbres en Kotlin |
| --- | ---: | ---: |
| Modèles J0 + J+1/J+2 | 31,6 + 29,0 Mo, recopiés dans `filesDir` au premier usage | 5,4 + 5,0 Mo, lus depuis les assets |
| Bibliothèque native | 18,2 Mo (arm64), 75 Mo pour les 4 ABI de l'APK universel | aucune |
| Mémoire des deux sessions (mesure PC) | ≈ 500 Mo | ≈ 11 Mo de tableaux |
| Écart à CatBoost | 1,7·10⁻⁶ | 1,2·10⁻⁸ |

La 1.22 était en outre bloquée : les versions suivantes grossissaient de 7 à 12 Mo par ABI, et la
1.29 démarre un client de télémétrie Microsoft.

Le réseau (`network.py`, format `*.mlp`) suit le même principe : `NeuralNetworkModel.kt` lit les
poids et fait le calcul en Kotlin (entrées standardisées avec indicateurs de valeur manquante, deux
couches cachées de 256 et 128 unités, GELU, softmax, moyenne des 3 graines). `DebitModel.kt` fait la
moyenne avec les arbres, avec le poids donné par `thresholds.json` ; sans entrée `network`, l'app
n'utilise que les arbres. Les deux réseaux pèsent 0,96 Mo chacun et s'écartent de PyTorch de
4·10⁻⁷ au plus.

### Dans l'app : historique, listes, sorties prévues (v8, novembre 2026)

* **Historique du canyon à jour.** L'estimation partait de la liste de débits enregistrée sur le
  téléphone au moment d'ouvrir la page, et n'était pas recalculée quand la liste était relue sur
  le site. Pour un canyon jamais ouvert, c'était l'historique embarqué, arrêté au 13/10/2026 :
  l'estimation ignorait alors les reports des dernières semaines, son meilleur signal (exactitude
  de 80 à 83 % quand le dernier report a moins de 7 jours, 67 à 71 % au-delà d'un mois). La liste
  est maintenant complétée par les cases du canyon dans la grille « derniers débits » (16 jours,
  déjà lue pour les voisins), et l'estimation est recalculée dès que la liste relue apporte de
  nouveaux reports, au tirer-pour-rafraîchir et au changement de jour. Les téléchargements de
  l'estimation sont gardés 10 minutes : le recalcul est immédiat.
* **Listes.** Les favoris et la recherche triée par distance (« autour de moi », ou autour du
  lieu d'un séjour) montrent l'estimation du jour, ou du jour du séjour s'il est dans les trois
  jours estimés, dans un badge de la carte canyon. Le filtre « Débit correct probable » garde les
  canyons dont l'estimation affiche « correct » (seul ou en fourchette) sans risque de gros débit
  signalé, parmi les 30 plus proches. Les estimations enregistrées depuis moins de 3 heures
  s'affichent tout de suite, les autres sont calculées l'une après l'autre : une fenêtre météo
  complète compte pour 29 appels du quota gratuit d'Open-Meteo (600 par minute), elles sont
  espacées de 4 s.
* **Sorties prévues.** Le plan de sortie calcule l'estimation à l'ouverture (il ne montrait que la
  dernière enregistrée). Tant qu'une sortie est prévue, une tâche de fond recalcule toutes les 3 h
  l'estimation de chaque canyon des sorties des trois prochains jours, avec les reports relus sur
  le site, et l'enregistre pour le plan hors connexion. Quand le jour d'une sortie devient à risque
  de gros débit, une notification le dit une fois (pas entre 22 h et 7 h) ; si le risque retombe
  puis revient, elle le redit.
* **Sur quoi repose l'estimation.** La carte dépliée liste les entrées principales du premier
  jour, telles que le modèle les a vues : dernier report du canyon (ou aucun : l'estimation
  s'appuie sur le massif), reports des voisins sur 7 jours comparés à leur niveau habituel, pluie
  des 3 derniers jours et du jour, pluviomètres proches, neige simulée et fonte.
* **Fiabilité par canyon.** `canyon_reliability.json` donne, pour les canyons qui ont au moins 10
  reports dans les périodes de test du walk-forward, combien de fois ce que l'app aurait affiché
  (niveau, ou fourchette quand le modèle hésite) contenait le niveau signalé, avec le modèle et les
  seuils de chaque période (qui n'avaient jamais vu ces reports). 743 canyons, 31 244 reports :
  86 % de justes en moyenne, de 67 % (5e centile) à 100 % selon le canyon ; 11 Ko.

## Évaluation

Voir la section générée ci-dessous (walk-forward : un modèle par période, qui ne connaît que les
observations antérieures à cette période, comme l'app le premier jour).

Données : 166 802 observations Descente-Canyon (1988 → 07/10/2026), dont 8 canyons interdits
restaurés depuis le scraping de juin. Météo ERA5 pour 3 330 cibles.

### v8 (21/11/2026) : J+1/J+2 avec les reports vraiment connus

Le modèle J+1/J+2 apprend maintenant avec les reports du canyon connus le matin d'aujourd'hui
(avant J − h), comme l'app (voir « Deux modèles »), et son évaluation aussi. Le modèle J0 est
inchangé (fichiers `model.trees` et `model.mlp` identiques octet pour octet).

| J+1, 36 265 reports de test | v7 (évaluation optimiste) | v8 (évaluation honnête) |
| --- | ---: | ---: |
| Erreur de probabilité (RPS) | 0,0384 | 0,0389 |
| AUC GROS+ | 0,910 | 0,907 |
| Rappel GROS+ à précision de 60 % | 59,6 % | 56,8 % |
| Exactitude (niveau exact) | 75,8 % | 75,7 % |
| GROS+ sous-estimés de ≥ 2 niveaux | 9,5 % | 9,9 % |
| Persistance, exactitude | 67,4 % | 66,2 % |

Par période, le RPS honnête à J+1 vaut 0,04143 / 0,04203 / 0,03588 (v7 annonçait 0,04078 /
0,04133 / 0,03556), et à J+2 0,04194 / 0,04261 / 0,03639. Le modèle J0 appliqué à J+1 fait moins
bien (0,04162 / 0,04278 / 0,03632) : le modèle dédié reste utile. Ce qui change, c'est surtout
l'évaluation : sur 2025-2026, le modèle J+1 de la v7 évalué honnêtement donne 0,03626 de RPS (arbres
seuls) contre 0,03619 pour celui de la v8, à J+2 0,03673 contre 0,03669, des écarts dans le bruit
d'une graine. La confiance affichée reste calibrée à J+1 : juste 92 % du temps en confiance élevée
(59 % des cas), 78 % en moyenne (29 %) et 64 % en faible (12 %).

### v7 (21/11/2026) : moyenne CatBoost + réseau

Mêmes données et mêmes CatBoost que la v6 (les fichiers `*.trees` sont identiques octet pour
octet), auxquels s'ajoute un réseau par modèle (`*.mlp`). Les probabilités sont la moyenne à parts
égales des deux, et seuils de décision et seuils de risque sont réglés à nouveau sur cette moyenne.
Le walk-forward entraîne les deux à chaque période, exactement comme le modèle final.

| RPS (négatif = mieux) | CatBoost seul | CatBoost + réseau | Écart |
| --- | ---: | ---: | ---: |
| 2023, J0 | 0,04020 | 0,03955 | −1,6 % |
| 2024, J0 | 0,04072 | 0,04020 | −1,3 % |
| 2025 → oct. 2026, J0 | 0,03522 | 0,03483 | −1,1 % |
| 2023 / 2024 / 2025-2026, J+1 | 0,04139 / 0,04186 / 0,03586 | 0,04078 / 0,04133 / 0,03556 | −1,5 / −1,3 / −0,8 % |
| 2023 / 2024 / 2025-2026, J+2 | 0,04157 / 0,04214 / 0,03613 | 0,04094 / 0,04178 / 0,03587 | −1,5 / −0,8 / −0,7 % |

Sur les 36 265 reports de test :

| | J0 : v6 → v7 | J+1 : v6 → v7 |
| --- | ---: | ---: |
| Erreur de probabilité (RPS) | 0,0380 → 0,0375 (−1,3 %) | 0,0389 → 0,0384 (−1,2 %) |
| AUC GROS+ | 0,915 → 0,917 | 0,908 → 0,910 |
| Précision GROS+ à rappel de 70 % | 53,0 → 53,6 % | 50,4 → 50,8 % |
| Rappel GROS+ à précision de 60 % | 61,3 → 61,8 % | 57,0 → 59,6 % |
| Exactitude (niveau exact) | 75,4 → 76,1 % | 75,1 → 75,8 % |
| MAE ordinale | 0,270 → 0,263 | 0,275 → 0,268 |
| GROS+ sous-estimés de ≥ 2 niveaux | 8,4 → 8,2 % | 9,3 → 9,5 % |
| GROS+ : précision / rappel / F1 (seuils réglés) | 0,57 / 0,65 / 0,607 → 0,58 / 0,64 / 0,611 | 0,55 / 0,63 / 0,592 → 0,58 / 0,62 / 0,601 |

Le gain est celui prévu par l'étude : il porte sur toutes les périodes et les trois horizons. Les
chiffres J+1 et J+2 de ce tableau supposent les reports du canyon connus jusqu'à J-1 : voir la v8
pour leur valeur honnête. La
confiance affichée reste calibrée : ce qui est affiché est juste 92,5 % du temps en confiance
élevée (60 % des cas), 77 % en moyenne (29 %) et 64,5 % en faible (11 %).

### v6 (29/10/2026) : bassins versants corrigés

Même modèle et mêmes reports, nouvelles données de bassin : 265 bassins recalculés et contrôlés à
l'œil (118 canyons qui n'en avaient pas, 147 exutoires mal placés), descripteurs de ces bassins, et
cible météo au centre du bassin actuel pour 2 567 canyons (série ERA5 de la même maille, sur l'union
des séries de la maille). 168 007 lignes, 36 265 reports de test.

Comparaison v5 → v6 sur les 36 258 reports de test communs :

| | v5 J0 | v6 J0 | v5 J+1 | v6 J+1 |
| --- | ---: | ---: | ---: | ---: |
| Exactitude | 0,757 | 0,754 | 0,754 | 0,751 |
| MAE ordinale | 0,267 | 0,270 | 0,273 | 0,275 |
| GROS+ F1 | 0,603 | 0,607 | 0,588 | 0,591 |
| RPS | 0,0381 | 0,0380 | 0,0390 | 0,0389 |
| AUC P(≥ GROS) | 0,915 | 0,915 | 0,907 | 0,908 |
| 265 bassins corrigés (1 797 reports) : GROS+ F1 / RPS | 0,48 / 0,0458 | 0,55 / 0,0448 | 0,47 / 0,0471 | 0,53 / 0,0459 |

Le RPS baisse un peu en 2023 et 2024 et reste égal en 2025-2026 ; l'exactitude perd 0,2 à 0,5 point
selon la période, sous le bruit d'une graine (≈ 0,8 point). Le gain est sur les canyons corrigés : la
détection des gros débits y progresse sur les trois périodes (F1 +9, +9 et +2 points à J0).

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
récupérée comme dans l'app, les assets exportés et le modèle de l'app (`validate_recent.py`). Résultats :
exactitude 0,835 contre 0,808 pour la persistance, MAE 0,18 contre 0,22, erreurs graves 1,0 % contre
1,6 %. Sur les 46 « gros débits » de cet été sec, 7 étaient des messages (incendie, travaux,
stationnement…), auxquels le modèle donnait à raison une probabilité quasi nulle. D'où le filtre
`label_quality.py`.

### Décision embarquée

* modèle J0 (v7, inchangé en v8) : seuils cumulatifs τ = 0,58 / 0,51 / 0,27 / 0,27 / 0,25 (SEC→FILET … TRÈS
  GROS→CRUE) ; risque « Fort » si P(≥ GROS) ≥ 0,27 (`balanced`), 0,62 (`prudent`), 0,07
  (`safety_first`) ;
* modèle J+1/J+2 (v8) : τ = 0,54 / 0,55 / 0,30 / 0,26 / 0,28 ; risque « Fort » si
  P(≥ GROS) ≥ 0,31 (`balanced`), 0,65 (`prudent`), 0,06 (`safety_first`) ;
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

### LSTM pour l'état hydrologique (novembre 2026)

Question : un réseau récurrent qui lit la météo quotidienne peut-il remplacer ou améliorer l'état
hydrologique fait main (`hydro.py` : neige sur 5 bandes d'altitude, réservoir sol GR4J, réservoirs
de 1 à 90 jours) ? Pour le débit mesuré, les LSTM entraînés sur des centaines de bassins sont l'état
de l'art en hydrologie (Kratzert et al., jeux CAMELS), y compris sur des bassins non jaugés.

Protocole (`scripts/debit_model/research/lstm_README.md`) : mêmes lignes, périodes et réglages que
le banc d'essai, architecture choisie sur la validation seule, écarts de RPS avec intervalle à 95 %
(bootstrap apparié par canyon). Le réseau lit 1 à 2 ans de météo de la cible du canyon (pluie,
température, jour de l'année) avec un résumé appris des descripteurs statiques ; un LSTM de 64
unités, puis une tête MLP qui reçoit aussi les features tabulaires. Entraînement sur CPU : 20 à
80 minutes par modèle.

| Essai (écart de RPS, négatif = mieux) | Référence | 2023 | 2024 | 2025-2026 |
| --- | --- | ---: | ---: | ---: |
| LSTM météo seul (moyenne de 2 graines) à la place de l'hydro fait main | CatBoost statique + hydro fait main | | +0,0018 | +0,0006 |
| Sorties du LSTM (hors échantillon) ajoutées aux features de production | CatBoost production, réglages rapides | | +0,0003 | +0,0000 |
| Même chose sans l'hydro fait main | idem | | +0,0014 | +0,0006 |
| Production sans l'hydro fait main, pour comparaison | idem | | +0,0029 | +0,0015 |
| Deux graines de CatBoost au lieu d'une | CatBoost production | −0,00018 | −0,00009 | −0,00025 |
| Moyenne CatBoost + MLP sans LSTM (3 graines) | CatBoost production | −0,00078 | −0,00052 | −0,00036 |
| Même chose pour le modèle J+1/J+2, évalué à J+1 | CatBoost J+1/J+2 production | | −0,00032 | −0,00028 |
| Moyenne CatBoost + réseau LSTM complet (3 graines) | CatBoost production | −0,00103 | −0,00034 | −0,00052 |
| Réseau LSTM au lieu du MLP dans la moyenne (2 graines de CatBoost) | CatBoost ×2 + MLP | −0,00024 | +0,00018 | −0,00017 |
| Réseau LSTM ajouté à cette moyenne | CatBoost ×2 + MLP | −0,00021 | −0,00001 | −0,00016 |

Intervalles à 95 % des deux dernières lignes : [−0,00041 ; −0,00008], [−0,00003 ; +0,00040],
[−0,00030 ; −0,00005], puis [−0,00030 ; −0,00014], [−0,00012 ; +0,00010], [−0,00023 ; −0,00011].
Les moyennes CatBoost + réseau gagnent sur les trois périodes avec des intervalles qui excluent
zéro ; poids 1/2 – 1/2, la validation donne 0,4 à 0,6 avec un optimum plat.

En unités plus parlantes (36 265 reports J0 des trois périodes, 26 151 à J+1 ; repères : ce
qu'apporte l'hydro fait main, et ce que change la seule graine de CatBoost). Les détections GROS+
sont comparées à rappel ou précision égal, car les seuils de décision réglés sur la validation
déplacent à eux seuls précision et rappel de ±5 points d'un entraînement à l'autre :

| | CatBoost + MLP, J0 | CatBoost + MLP, J+1 | Hydro fait main, 2 périodes (sans → avec) | Autre graine de CatBoost |
| --- | ---: | ---: | ---: | ---: |
| Erreur de probabilité (RPS) | −1,4 % | −0,8 % | −5,0 % | −0,2 % |
| AUC GROS+ | 0,915 → 0,917 | 0,909 → 0,912 | 0,904 → 0,916 | 0,915 → 0,915 |
| Précision GROS+ à rappel de 70 % | 52,8 → 53,7 % | 51,8 → 52,0 % | 49,9 → 53,7 % | 52,8 → 52,3 % |
| Rappel GROS+ à précision de 60 % | 61,2 → 62,2 % | 59,0 → 61,2 % | 55,3 → 60,7 % | 61,2 → 61,6 % |
| Exactitude (niveau exact) | 75,0 → 75,7 % | 75,6 → 76,7 % | 74,7 → 75,9 % | 75,0 → 76,2 % |

Le gain vaut donc environ le quart de celui de l'hydro fait main et le double du bruit sur les
mesures indépendantes des seuils ; sur l'exactitude, il reste dans le bruit des seuils.

* **Le LSTM ne remplace pas l'hydro fait main.** Aucune des variantes essayées (GRU, 32 ou 128
  unités, 2 couches, 2 ans de chauffe, perte RPS, 24 ou 32 époques, états de l'hydro fait main en
  entrée) ne comble l'écart ; la meilleure reste à +0,0012 sur 2024. Les plongements PLR des
  variables numériques surapprennent dès la 6e époque. Il récupère environ la moitié de ce
  qu'apporte l'hydro fait main, et ce qu'il apprend y est déjà : empilé sur les features de
  production, il n'ajoute rien. Contrairement à CAMELS, le label n'est pas un débit mesuré chaque
  jour mais quelques reports ordinaux bruités par canyon, relatifs au canyon ; un modèle physique
  avec ses a priori (neige, sol) reste plus efficace avec si peu de signal.
* **L'essentiel du gain vient du mélange de deux apprenants, pas du LSTM.** Un MLP sur les mêmes
  116 features, 20 fois plus rapide à entraîner, en apporte 55 à 95 % : arbres et réseaux se
  trompent différemment, résultat connu sur données tabulaires. Le LSTM ajoute environ −0,0002 sur
  2023 et 2025-2026, rien sur 2024 : un gain de l'ordre du bruit d'entraînement, non reproduit sur
  toutes les périodes, pour un coût bien plus élevé dans l'app (400 jours de météo à dérouler par
  canyon, un second réseau pour J+1).
* **Où gagne la moyenne CatBoost + MLP** : régulièrement sur les basses eaux (SEC −0,0023 /
  −0,0021 / −0,0025, FILET −0,0025 / −0,0021 / −0,0005) ; sur GROS et au-dessus le signe change
  selon la période (GROS +0,0013 en 2023, −0,0013 et −0,0015 ensuite). Le F1 GROS+ gagne 1,9 / 0,8
  / 0,9 point, l'exactitude 1,2 / 0,0 / 0,9 point. À J+1, l'exactitude gagne 1,0 / 1,1 point.
* **Dans l'app depuis la v7** (« v7 » dans Évaluation) : un MLP de 80 000 paramètres lit le même
  vecteur de 116 features que CatBoost. `train.py` entraîne trois graines pour J0 et trois pour
  J+1/J+2 (`network.py`, 16 époques fixes : PyTorch devient une dépendance du pipeline), règle
  seuils de décision et seuils de risque sur les probabilités moyennées et écrit les poids (2 ×
  0,96 Mo en float32). `NeuralNetworkModel.kt` les évalue en Kotlin, et le test de référence
  vérifie l'écart à Python. Gain mesuré : −1,3 % de RPS à J0 et −1,2 % à J+1, l'ordre de ce que
  coûte l'absence des postes automatiques avant 05:44 UTC (0,0008).

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
* Avant la publication du fichier quotidien Météo-France (05:56 UTC, relu après 06:15 UTC, soit
  vers 8 h en été), celui de la veille s'arrête à J-3 : l'app n'a alors aucun pluviomètre
  quotidien (le modèle a appris à se passer des postes automatiques, pas de ceux-là). Laissé tel
  quel (novembre 2026) : cela ne concerne que les estimations du petit matin.
* Une clé API Open-Meteo payante permettrait de tester ECMWF IFS 9 km (historique depuis 2017,
  identique en prévision), aujourd'hui hors des quotas gratuits.
* Les lâchers de barrages et les prises d'eau ne sont pas modélisés.
* Le label reste une appréciation humaine, relative au canyon et au groupe.
