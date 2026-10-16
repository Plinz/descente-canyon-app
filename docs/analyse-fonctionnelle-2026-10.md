# Analyse fonctionnelle (octobre 2026)

Objectif : vérifier si l'application couvre les besoins des pratiquants de canyon, en la comparant à
l'écosystème existant et aux retours des utilisateurs, puis combler les manques prioritaires.

## 1. Sources consultées

- **Retours utilisateurs** : fil « App Android Descente-Canyon – appel à testeurs » du forum
  descente-canyon.com (rubrique « A propos du site »), fiche Play Store (5/5, 500+ installations,
  3 avis positifs), sujet « Carte topographique » (canyon-explorer.com), « DESCENTE CANYON en mort
  cérébrale ? » et « Base Canyon… ouverture ? ».
- **Applications et sites comparables** : OpenCanyon.org (base communautaire CH/AT/IT/FR, appli
  Android/iOS), Canyon Guide (iOS, 2026), Topo Canyon (topos et traces GPS), canyon-explorer.com (carte
  web avec débits estimés par mois), RopeWiki (US), Canyoneer (iOS, données RopeWiki).
- **Sécurité** : FFME (« l'eau est le danger numéro un en canyon » ; débit, météo et orages en amont
  évitent la plupart des drames), statistiques d'accidents (noyade, sauts, rappels), EDF (lâchers d'eau
  imprévisibles), recommandation générale de laisser son itinéraire et son heure de retour à un proche.
- **Analyse du modèle de débit** (`docs/debit-prediction-model.md`, octobre 2026) : les deux leviers
  produit identifiés sont plus de signalements de débit (rappel après une sortie, saisie rapide) et
  l'affichage d'une fourchette quand le modèle hésite.

## 2. Constats

| Constat | Conséquence |
| --- | --- |
| La base de descente-canyon.com est fermée aux ajouts depuis 2019 ; l'activité vit des signalements de débit, et les membres regrettent que beaucoup consultent sans signaler. | Faciliter et provoquer le signalement après une sortie a un effet direct sur la qualité des débits et des estimations pour tous. |
| Demandes explicites du forum : couleurs de débit comme le site (fait), filtre des derniers débits par zone (fait), **canyons à proximité d'une fiche** pour un plan B. | Le plan B manquait ; le filtre de zone n'était pas mémorisé. |
| OpenCanyon et Canyon Guide proposent un carnet de sorties ; Canyon Guide suit aussi la trace GPS et liste les canyons « à faire ». | Le carnet personnel est une fonction attendue d'une appli de terrain. |
| canyon-explorer.com affiche un débit « habituel » par mois. | La saisonnalité aide à choisir quand partir. |
| Aucune application ne combine débit signalé, estimation, météo et orages, préparation de sortie et urgence hors ligne. | C'est le positionnement à tenir : l'aide à la décision et la sécurité, pas seulement la consultation. |
| La sortie de nuit, la crue d'orage et l'absence d'alerte d'un proche sont des causes classiques d'accident. | Heure de retour face au coucher du soleil, alerte orage, message à un proche, écran d'urgence. |

## 3. Ce qui a été ajouté

1. **Fiche canyon orientée décision** : prévisions sur 6 jours, alerte sur le prochain orage avec son
   heure, lever et coucher du soleil, estimation en fourchette (« correct à gros ») quand la probabilité
   du niveau prévu est sous 60 %, canyons à moins de 30 km avec leur dernier débit connu, répartition
   des débits signalés selon le mois, parcours et horaires en tête de fiche. Les dernières prévisions et
   estimations restent affichées hors ligne avec leur date.
2. **Préparation de sortie** : horaires estimés d'après les temps de la fiche (lus comme des
   fourchettes), marge avant le coucher du soleil calculé sur le téléphone, conditions du jour, message
   pour un proche (lieu, horaires, heure d'alerte, numéro d'urgence, coordonnées des parkings) et rappel
   à l'heure de retour prévue qui ouvre le formulaire de débit.
3. **Écran d'urgence hors ligne** : numéros du pays du canyon, position GPS en décimal et en degrés-minutes
   avec précision et altitude, envoi par SMS, échappatoires et points de la fiche, informations à donner aux
   secours. Accessible en un geste (voir section 6).
4. **Carnet de sorties** : ajout depuis la fiche ou automatiquement avec « Je suis rentré », liste par
   année et totaux ; chaque ajout propose de signaler le débit.
5. **Confort** : zone des derniers débits mémorisée, tri des favoris (nom, dernier débit), durées des
   fiches lisibles (« 5 » devient « 5 min », « néant », « ?? »).
6. **Correctifs révélés en route** : blocage de plusieurs secondes (ANR) à l'ouverture des canyons très
   signalés (lecture de page quadratique, sur le fil principal).

## 4. Pistes restantes, par priorité

1. **Cartes topographiques hors ligne** (attente forte, absente d'OpenCanyon aussi). Les règles
   d'OpenStreetMap et d'OpenTopoMap interdisent le téléchargement massif. Une zone limitée autour d'un
   canyon depuis le Plan IGN (Géoplateforme, données ouvertes) serait la piste française la plus
   réaliste, à valider avec les conditions d'usage de l'IGN.
2. **Signalement en un geste** depuis la notification de fin de sortie (niveau choisi dans la
   notification, envoi en file hors ligne), pour aller plus loin que l'ouverture du formulaire.
3. **Liste « à faire »** distincte des favoris, et suivi de trace GPS pendant la sortie (consommation de
   batterie à maîtriser, service au premier plan).
4. **Widget d'écran d'accueil** : débit et estimation des favoris.
5. **Couche « débits récents » sur la carte** (couleur des marqueurs selon le dernier signalement).

## 5. Contributions externes en attente

Les PR #6 (prévisions à 5 jours), #7 (partage et « Avertir un proche ») et #8 (dossiers et tri des
favoris), ouvertes en septembre 2026 par un contributeur sur l'ancienne architecture, ne peuvent plus
être fusionnées telles quelles. Leurs idées principales sont désormais couvertes (prévisions, partage,
message à un proche, tri) ; les dossiers de favoris restent à discuter avec le contributeur.

## 6. Retours après usage (octobre 2026)

- **Carte météo** : les ajouts (prévisions, orages, soleil) s'empilaient sur l'ancienne présentation, avec des
  doublons (cumuls affichés deux fois) et trois styles de tuiles. Elle est refaite en une seule carte sans
  dépliage : alerte orage, « Pluie » (tombée en 72 h, prévue en 48 h, risque) avec un graphique heure par heure
  qui montre quand il a plu et les heures d'orage, « Prochains jours », puis soleil, point météo et heure de mise à
  jour. Le pic horaire, les cumuls 24/48 h et les coordonnées du point météo sont retirés. Les cumuls sont calculés
  à l'affichage, donc justes aussi pour une prévision lue hors ligne le lendemain.
- **Urgence** : il fallait ouvrir une fiche puis un menu pour l'atteindre, et l'écran ignorait où l'on était.
  Elle est maintenant à un geste partout où l'on se trouve pendant une sortie : bouton SOS (accueil, fiche, carte
  des points), raccourci « Urgence » de l'icône de l'application, et notification « Sortie en cours » qui
  s'ouvre écran verrouillé et propose « Je suis rentré ». Écran verrouillé, les numéros passent par le composeur
  d'urgence du système, sans déverrouiller. L'écran nomme le canyon (fiche, sortie en cours, sinon le canyon dont
  le parcours passe au plus près de la position, avec « Pas le bon canyon ? »), indique le point de la fiche le
  plus proche, trie les points par distance avec la direction, reprend l'engagement de la fiche et prépare un SMS
  complet (canyon, position, point proche, lien de carte).
- **Numéros d'urgence** : les 30 pays du site et une centaine d'autres pays de canyoning ont leurs numéros (source :
  conseils aux voyageurs du Foreign Office britannique, recoupés avec France Diplomatie quand ils divergent ; un
  numéro contradictoire est écarté). Hors d'Europe, le 112 n'est plus proposé là où il n'est pas le numéro local
  (Madagascar : 118, 117, 119 ; Népal : 100, 1144...). Sans canyon, le pays du réseau mobile donne les numéros.

