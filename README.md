# google-drive-file-uploader ![Clojure CI](https://github.com/ashwinbhaskar/google-drive-file-uploader/workflows/Clojure%20CI/badge.svg?branch=master)

A Clojure CLI program to upload files to google-drive

## Limitations d'authentification — à lire avant tout

Ce programme accepte trois modes d'authentification mais **leur compatibilité dépend du type de compte Google que tu utilises**.

### Comptes Google personnels (gmail.com)

Si tu utilises un compte Google personnel, **le mode service account (option `--key-file` ou variable d'environnement `GD_KEY_FILE`) ne fonctionnera pas** pour uploader des fichiers. Cette limitation vient de Google et non du programme.

Les comptes de service ne disposent pas de leur propre quota de stockage Drive et ne peuvent donc écrire que sur des **Drives partagés** (_Shared Drives_), une fonctionnalité réservée aux comptes Google Workspace (entreprise/organisation). Sur un compte personnel, toute tentative d'upload via service account aboutira à l'erreur `Service Accounts do not have storage quota. Leverage shared drives` retournée par l'API Drive.

Pour les comptes personnels, le seul mode d'authentification qui fonctionne est le **flux OAuth2 utilisateur**, qui nécessite les paramètres `--refresh-token`, `--client-id` et `--client-secret` (ou les variables d'environnement correspondantes `GD_REFRESH_TOKEN`, `GD_CLIENT_ID`, `GD_CLIENT_SECRET`). Le programme s'authentifie alors avec tes propres droits utilisateur et consomme ton quota personnel.

Pour obtenir ces credentials, il faut créer un projet sur la Google Cloud Console, activer l'API Drive, créer un identifiant OAuth2 de type _Desktop_ ou _Web_, autoriser le scope `https://www.googleapis.com/auth/drive`, puis effectuer une fois le flow OAuth2 manuellement (par exemple via le [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/)) pour récupérer le refresh-token de longue durée.

### Comptes Google Workspace (entreprise)

Avec un compte Workspace, les trois modes sont utilisables. Le mode service account fonctionne à condition d'uploader vers un Drive partagé dont le service account est membre avec un rôle au moins équivalent à _Gestionnaire de contenu_. Note que le code actuel n'envoie pas le paramètre `supportsAllDrives=true` à l'API Drive, ce qui est requis pour les Drives partagés ; cette limitation devra être levée côté code pour que le scénario Workspace+Shared Drive fonctionne complètement.

## Usage

Clone the repo and run `lein uberjar`
Assuming you name the standalone jar file generated as `google-drive-file-uploader.jar`

```shell
java -jar google-drive-file-uploader.jar uf --folder "APKs"
 --file-path "/users/johndoe/foo.apk"
 --file-name "foo-debug.apk"
 --access-token "1//0g5OOBsnCGBASNwF-i4Be9t3ByEpiSha7" //can be ignored if set in env variable GD_ACCESS_TOKEN
 --refresh-token "1//0g5O1fYfm6BsnCgYIARAAGBASNwF-LNYaJvVVTAkpkbGpG" //can be ignored if set in env variable GD_REFRESH_TOKEN
 --client-id "806260tdi0.apps.googleusercontent.com" //can be ignored if set in env variable GD_CLIENT_ID
 --client-secret "12oXYAcp6Vc6BXxMZf20UQEq" //can be ignored if set in env variable GD_CLIENT_SECRET
```

will upload your file to google drive to the folder name mentioned in the command line argument.

## Important Note

1. The parameters `access-token`, `refresh-token`, `client-id` and `client-secret` can be set in environment variables `GD_ACCESS_TOKEN`, `GD_REFRESH_TOKEN`, `GD_CLIENT_ID` and `GD_CLIENT_SECRET` respectively.
2. You can choose to set skip `refresh-token`, `client-id` and `client-secret` if you give a valid `access-token`
3. If you don't give the `access-token` then `refresh-token`, `client-id` and `client-secret` are mandatory
4. Voir la section _Limitations d'authentification_ en haut de ce document pour savoir quel mode utiliser selon ton type de compte Google.

## Authentification via fichier EDN (alternative aux variables d'environnement)

En complément des variables d'environnement et des arguments CLI,
le programme peut aussi lire les credentials OAuth2 depuis un fichier EDN.
Cette option est pratique si tu préfères, comme moi, conserver tes credentials
dans un fichier de configuration plutôt que dans des variables d'environnement.

Le fichier doit être placé à
`$XDG_DATA_HOME/google-drive-uploader/auth.edn`
(avec un fallback sur `~/.local/share/google-drive-uploader/auth.edn`
quand `$XDG_DATA_HOME` n'est pas défini).

Le format attendu est une map EDN avec les quatre clés OAuth2 :

```edn
{:client-id     "xxx.apps.googleusercontent.com"
 :client-secret "GOCSPX-..."
 :refresh-token "1//0g..."
 :access-token  "ya29..."}
```

### Règle de priorité

Le fichier `auth.edn` est lu **uniquement** quand aucune des quatre valeurs
OAuth2 n'est présente dans la map d'arguments finale (cli-matic fusionne
les arguments CLI et les variables d'environnement). Concrètement :

- Si au moins une des valeurs `--access-token`, `--refresh-token`,
  `--client-id`, `--client-secret` est passée en CLI, ou si au moins une
  des variables `GD_ACCESS_TOKEN`, `GD_REFRESH_TOKEN`, `GD_CLIENT_ID`,
  `GD_CLIENT_SECRET` est positionnée, alors `auth.edn` est ignoré.
- Sinon, les quatre clés du fichier `auth.edn` sont chargées dans les
  arguments puis le pipeline d'authentification habituel s'applique.

Le mode service-account (`--key-file` / `GD_KEY_FILE`) est totalement
indépendant du fichier `auth.edn` : ce dernier ne peut pas le remplacer
ni le surcharger.

### Persistance automatique de l'access-token

Quand le programme rafraîchit l'access-token via le flow refresh-token,
il met **automatiquement** à jour le champ `:access-token` du fichier
`auth.edn` si celui-ci existe (les autres clés sont préservées).
Aucune action manuelle de copier-coller n'est requise pour les
exécutions suivantes. Le fichier n'est jamais créé automatiquement :
tu dois le créer toi-même la première fois avec tes credentials initiaux.

### Création initiale du fichier

```bash
mkdir -p "$XDG_DATA_HOME/google-drive-uploader"
chmod 700 "$XDG_DATA_HOME/google-drive-uploader"
cat > "$XDG_DATA_HOME/google-drive-uploader/auth.edn" << 'EOF'
{:client-id     "xxx.apps.googleusercontent.com"
 :client-secret "GOCSPX-..."
 :refresh-token "1//0g..."
 :access-token  "ya29..."}
EOF
chmod 600 "$XDG_DATA_HOME/google-drive-uploader/auth.edn"
```

## Verbose / Debug logs

Le flag `--verbose` (ou `-v`) doit être placé **avant** le sous-command, par exemple `java -jar … --verbose uf --folder-id …`. Les logs de debug préfixés par `[DEBUG]` sont écrits sur `stderr` et sont actifs en permanence ; pour les masquer une fois le diagnostic terminé, ajoute `2>/dev/null` à la commande.

## Docker

The jar of the program is available as a docker image - https://hub.docker.com/r/ashwinbhskr/google-drive-uploader

As an example, for folks looking to upload their android apk using this program, I have built a
docker image using android build box - https://hub.docker.com/r/ashwinbhskr/android-build-box-with-drive-uploader

You can use the above image to upload your apks in your build pipeline.

```
 - ./gradlew test
 - ./gradlew assembleDebug
 - cd app/build/outputs/apk/debug/
 - java -jar /drive-uploader.jar uf --folder "Foo APKs" --file-path "app-debug.apk" --file-name "foo.apk"
```
