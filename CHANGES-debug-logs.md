# Logs de debug pour diagnostiquer les uploads silencieux

## Contexte du problème

L'utilisateur exécutait la commande suivante et constatait
qu'aucune sortie n'apparaissait
et que rien n'était uploadé sur Google Drive:

```bash
GD_KEY_FILE="$XDG_DATA_HOME/google-drive-uploader/service-account.json" \
  java -jar target/google-drive-file-uploader-0.1.0-SNAPSHOT-standalone.jar uf \
  --folder-id "1yw-WGs4idJLBULWor1SLN8yVU0Mv7eB1?hl=fr" \
  --file-path "./project.clj" \
  --file-name "coucou.txt"
```

L'analyse du code a révélé deux causes structurelles
au caractère silencieux du programme.
D'abord, toutes les sorties existantes étaient conditionnées
par le flag `--verbose` (qui n'était pas activé dans la commande de l'utilisateur).
Ensuite, la fonction `success` du namespace `core`
appelait `System/exit 0` sans afficher quoi que ce soit
en cas de succès, rendant invisible la complétion réussie.
À cela s'ajoutait un risque d'exception silencieuse
dans `get-access-token-from-key-file`, par exemple
lorsque la variable `$XDG_DATA_HOME` n'est pas définie
(ce qui produit alors un chemin de fichier vide ou inexistant).

Une seconde observation potentiellement gênante concerne le `--folder-id` fourni:
la valeur `1yw-WGs4idJLBULWor1SLN8yVU0Mv7eB1?hl=fr` contient le suffixe `?hl=fr`
qui est en réalité un paramètre d'URL de l'interface web Google Drive,
pas une partie du véritable identifiant de dossier.
L'API Drive ne reconnaîtra donc pas cet identifiant tel quel.
Une fois les logs activés, ce point sera mis en évidence.

## Modifications apportées

### Logs de debug toujours actifs

Une fonction `debug` a été ajoutée dans `core.clj` et `drive.clj`.
Elle écrit ses messages sur `*err*` (stderr) avec le préfixe `[DEBUG]`,
ce qui permet de les conserver tout en pouvant les masquer facilement
via une redirection `2>/dev/null` si jamais l'utilisateur veut une sortie propre.
Chaque message déclenche un `flush` immédiat
pour éviter que des logs soient perdus avant un `System/exit`.

Des points de log ont été placés à toutes les étapes critiques du flux:
le point d'entrée `-main` enregistre les arguments bruts reçus,
la fonction `upload` enregistre les arguments parsés
(les secrets `access-token`, `refresh-token`, `client-secret`
sont masqués via `mask-secret` qui ne conserve que les quatre derniers caractères),
puis `upload-file-to-folder` trace chaque étape du pipeline:
validation des arguments, choix de la branche d'authentification,
résolution du `folder-id`, et appel à `upload-file-multipart`.
Les fonctions `check-access-token`, `valid-access-token?`,
`get-access-token-from-key-file`, `lookup-folder-id-by-name`,
`get-folders` et `upload-file-multipart` reçoivent toutes
des logs de debug montrant leurs paramètres et leurs résultats
(notamment les codes de statut HTTP).

### Capture d'exceptions

Le point d'entrée `-main`, la fonction `upload`
et la fonction `check-access-token` du namespace `core`
sont désormais protégés par un bloc `try/catch (Throwable ...)`
qui affiche la stack trace et le message en cas d'exception non capturée.
Sans cela, une exception remontant jusqu'à cli-matic
pouvait disparaître silencieusement.

La fonction `get-access-token-from-key-file` capture également
toute exception du SDK Google Cloud
(avec un log `[DEBUG]` indiquant la cause)
puis la rethrow, pour que la `f/try-all` du caller la transforme
en `Failure` exploitable.

### Court-circuit pour `valid-access-token?` sur token vide

Auparavant, lorsqu'aucun `access-token` n'était fourni,
la fonction `valid-access-token?` envoyait quand même
une requête HTTP à `googleapis.com/oauth2/v3/tokeninfo?access_token=`
avec une valeur vide. Cela générait une requête réseau inutile
et ralentissait inutilement le démarrage.
Désormais, si le token est blank, la fonction retourne `false`
immédiatement sans appel HTTP, et un log `[DEBUG]` l'indique.

### Affichage de succès par défaut

La fonction `success` affiche désormais
le message `"Upload completed successfully."` par défaut
quand elle est appelée sans argument.
Avant, elle se contentait d'appeler `System/exit 0` sans rien dire.

### Refactoring des namespaces fully-qualified

Les usages de `clojure.java.io/file`, `clojure.string/split`,
`clojure.string/blank?`, `clojure.string/trim` et `clojure.edn/read-string`
ont été remplacés par des alias `io`, `str` et `edn`
déclarés dans le `:require` des namespaces concernés.
Cela améliore la lisibilité, élimine deux warnings clj-kondo
(`Unresolved namespace clojure.java.io` et `Unresolved namespace clojure.string`),
et suit les conventions usuelles de la communauté Clojure.

## Fichiers modifiés

Trois fichiers de la base de code ont été modifiés:
`src/google_drive_file_uploader/core.clj` (ajout de logs et try/catch),
`src/google_drive_file_uploader/drive.clj` (ajout de logs, court-circuit token vide, alias),
et `src/google_drive_file_uploader/config.clj` (alias `io` et `edn`).

## Vérifications effectuées

Le linter `clj-kondo` a été exécuté: les seules erreurs/warnings restantes
sont des faux positifs préexistants liés aux macros `f/try-all` et `f/if-let-ok?`
(elles existaient avant cette modification).
Le formateur `clojure-lsp` a été lancé et n'a rien eu à reformater.
La suite de tests `lein test` passe avec 4 tests / 45 assertions / 0 échec / 0 erreur.
L'uberjar a été rebuildé avec succès.

Une exécution de bout en bout avec un faux key-file
(`GD_KEY_FILE=/tmp/nonexistent-sa.json`) a confirmé que tous les logs apparaissent
correctement à l'écran (sur stderr), que l'exception `FileNotFoundException`
est capturée puis transformée en `Failure`, que le message d'erreur
est affiché avec le préfixe `[ERROR]` sur stdout, et que le programme
sort avec le code 1 attendu.

## Comment relancer ta commande

L'uberjar à utiliser est bien `target/google-drive-file-uploader-0.1.0-SNAPSHOT-standalone.jar`.
Tu peux relancer ta commande exactement comme avant et tu verras
toute la chaîne de traitement s'afficher.
Si tu veux séparer les logs de debug de la sortie normale,
les logs `[DEBUG]` sont sur stderr (`2>`)
et les messages métier (`[ERROR]`, message de succès) sur stdout (`>`).

```bash
GD_KEY_FILE="$XDG_DATA_HOME/google-drive-uploader/service-account.json" \
  java -jar target/google-drive-file-uploader-0.1.0-SNAPSHOT-standalone.jar uf \
  --folder-id "1yw-WGs4idJLBULWor1SLN8yVU0Mv7eB1?hl=fr" \
  --file-path "./project.clj" \
  --file-name "coucou.txt"
```

Si tu veux silencer les debug une fois le diagnostic terminé:

```bash
GD_KEY_FILE=... java -jar ... uf ... 2>/dev/null
```

## Points d'attention pour ton diagnostic

Avant de relancer la commande, vérifie les deux points suivants.

D'abord la valeur effective de `$XDG_DATA_HOME`:
```bash
echo "$XDG_DATA_HOME" && ls -la "$XDG_DATA_HOME/google-drive-uploader/service-account.json"
```
Si la variable n'est pas définie, le chemin du key-file deviendra
`/google-drive-uploader/service-account.json`,
qui n'existe vraisemblablement pas, et tu verras alors un log
`[DEBUG] get-access-token-from-key-file: file exists? false`
suivi de l'exception `FileNotFoundException`.

Ensuite, le `?hl=fr` à la fin du `--folder-id` est très probablement parasite
(c'est un paramètre d'URL de l'interface Google Drive web, pas du folder-id).
Le folder-id réel est `1yw-WGs4idJLBULWor1SLN8yVU0Mv7eB1`.
Avec les logs activés, tu verras le `upload-file-multipart` envoyer
une requête multipart avec un `parents=[...?hl=fr]` qui aboutira
sans doute à une erreur 400 ou 404 de l'API Drive,
visible immédiatement dans `[DEBUG] upload-file-multipart: status = ...`
et `[DEBUG] upload-file-multipart: non-200 body = ...`.
