# google-drive-file-uploader ![Clojure CI](https://github.com/ashwinbhaskar/google-drive-file-uploader/workflows/Clojure%20CI/badge.svg?branch=master)

A Clojure CLI program to upload files to google-drive

## Limitations d'authentification — à lire avant tout

Ce programme accepte trois modes d'authentification mais **leur compatibilité dépend du type de compte Google que tu utilises**.

### Comptes Google personnels (gmail.com)

Si tu utilises un compte Google personnel, **le mode service account (option `--key-file` ou variable d'environnement `GD_KEY_FILE`) ne fonctionnera pas** pour uploader des fichiers. Cette limitation vient de Google et non du programme.

Les comptes de service ne disposent pas de leur propre quota de stockage Drive et ne peuvent donc écrire que sur des **Drives partagés** (*Shared Drives*), une fonctionnalité réservée aux comptes Google Workspace (entreprise/organisation). Sur un compte personnel, toute tentative d'upload via service account aboutira à l'erreur `Service Accounts do not have storage quota. Leverage shared drives` retournée par l'API Drive.

Pour les comptes personnels, le seul mode d'authentification qui fonctionne est le **flux OAuth2 utilisateur**, qui nécessite les paramètres `--refresh-token`, `--client-id` et `--client-secret` (ou les variables d'environnement correspondantes `GD_REFRESH_TOKEN`, `GD_CLIENT_ID`, `GD_CLIENT_SECRET`). Le programme s'authentifie alors avec tes propres droits utilisateur et consomme ton quota personnel.

Pour obtenir ces credentials, il faut créer un projet sur la Google Cloud Console, activer l'API Drive, créer un identifiant OAuth2 de type *Desktop* ou *Web*, autoriser le scope `https://www.googleapis.com/auth/drive`, puis effectuer une fois le flow OAuth2 manuellement (par exemple via le [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/)) pour récupérer le refresh-token de longue durée.

### Comptes Google Workspace (entreprise)

Avec un compte Workspace, les trois modes sont utilisables. Le mode service account fonctionne à condition d'uploader vers un Drive partagé dont le service account est membre avec un rôle au moins équivalent à *Gestionnaire de contenu*. Note que le code actuel n'envoie pas le paramètre `supportsAllDrives=true` à l'API Drive, ce qui est requis pour les Drives partagés ; cette limitation devra être levée côté code pour que le scénario Workspace+Shared Drive fonctionne complètement.

## Usage

Clone the repo and run `lein uberjar`
Assuming you name the standalone jar file generated as `google-drive-file-uploader.jar`
```
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
 4. Voir la section *Limitations d'authentification* en haut de ce document pour savoir quel mode utiliser selon ton type de compte Google.

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
