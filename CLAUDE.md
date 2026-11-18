# CLAUDE.md

Application Android non officielle pour descente-canyon.com (Kotlin, Gradle multi-modules). L'architecture, les
bases locales et les règles de dépendance sont décrites dans README.md : le lire avant toute modification structurelle.

## Outils et commandes shell

- Ne jamais lancer plusieurs appels `Bash` en parallèle dans un même message : dans cet environnement ils peuvent se
  bloquer ou être interrompus, surtout avec Gradle. Enchaîner les commandes shell une par une, même indépendantes.
- Les outils en lecture seule (`Read`, `Grep`, `Glob`) peuvent, eux, être appelés en parallèle.

## Validation Gradle

- Une seule commande Gradle à la fois.
- Tests unitaires : `./gradlew testDebugUnitTest :core:domain:test` (ou `./gradlew :<module>:testDebugUnitTest`
  pour un seul module, ex. `:feature:outing:testDebugUnitTest`).
- Formatage : `./gradlew spotlessApply` avant chaque commit ; la CI lance `spotlessCheck`.
- Tests instrumentés : sans appareil, vérifier seulement qu'ils compilent avec `./gradlew :app:assembleDebugAndroidTest`.

## Test sur téléphone

- Le téléphone est branché en USB au Mac. `adb` n'est pas dans le `PATH` :
  `~/Library/Android/sdk/platform-tools/adb`. Vérifier d'abord `adb devices -l` : l'appareil doit être `device`. S'il est
  `unauthorized` alors que l'autorisation a été acceptée sur le téléphone, faire `adb kill-server` puis `adb start-server`.
- Java et Python ne sont pas disponibles par défaut : Gradle utilise le JDK d'Android Studio et le script
  `generate_prepackaged_room_db.py` exige Python ≥ 3.10 (celui d'Apple est en 3.9). Installer et lancer ainsi :
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" PATH="/opt/homebrew/bin:$PATH"`
  puis `./gradlew :app:installDebug`.
- Lancer l'app : `adb shell monkey -p fr.descentecanyon.app -c android.intent.category.LAUNCHER 1`. Les logs se lisent
  avec `adb logcat`.
- Si `:app:mergeDebugResources` échoue après un build raté (état de cache corrompu), supprimer
  `app/build/intermediates/merged_res_blame_folder` et `app/build/intermediates/incremental/debug/mergeDebugResources`.
- `local.properties` (ignoré par git) doit contenir `sdk.dir=$HOME/Library/Android/sdk`, à la racine du projet.
- Une fois les tests automatisés validés, installer l'app sur le téléphone avec la procédure ci-dessus, puis demander à
  l'utilisateur de tester la fonctionnalité demandée sur l'appareil, en indiquant ce qu'il faut essayer. Ne pas
  considérer la tâche comme terminée avant son retour.

## Architecture (rappel)

- Une fonctionnalité (`feature/*`) ne dépend que de `core:domain` et `core:ui`, jamais de `core:data` ni d'une autre
  fonctionnalité ; seul `app` assemble le tout (Hilt).
- `user.db` est conservée : toute évolution de son schéma exige une migration testée (`UserDatabaseMigrationTest`).
  `catalog.db` est remplacée en bloc, sans migration.

## Rédaction des textes affichés

- Les textes destinés à l'utilisateur n'emploient jamais le masculin pour la personne qui utilise l'application, dans
  toutes les langues (les chaînes existent en 6 langues dans `core/ui`). Reformuler plutôt qu'ajouter un « ·e » :
  un nom (« Après connexion » plutôt que « Une fois connecté »), « vous », ou un mot épicène (« personne »,
  « membre », « proche »).
- L'accord avec les choses reste inchangé (« canyons suivis »). `InclusiveWordingTest` dans `core/ui` échoue sur les
  formes qui se glissaient auparavant.

## Commits

- Messages en anglais, une phrase à l'impératif décrivant l'effet pour l'utilisateur, sans préfixe
  (ex. « Open a canyon link once, not again when the activity is recreated »).
