(ns google-drive-file-uploader.auth
  "EDN-based authentication credentials.

  Reads/writes OAuth2 credentials and tokens from
  `$XDG_DATA_HOME/google-drive-uploader/auth.edn`.

  This is used as a fallback when none of the four `GD_*` OAuth2
  environment variables — `GD_ACCESS_TOKEN`, `GD_REFRESH_TOKEN`,
  `GD_CLIENT_ID`, `GD_CLIENT_SECRET` — is set (and no equivalent CLI
  arg either, since cli-matic merges both into the same `args` map).

  The service-account flow (`GD_KEY_FILE` / `--key-file`) is
  intentionally NOT covered by the EDN file: it stays managed
  exclusively via env var or CLI arg.

  Expected EDN format:
    {:client-id     \"...\"
     :client-secret \"...\"
     :refresh-token \"...\"
     :access-token  \"...\"}"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]))

(defn xdg-data-home
  "Resolve `$XDG_DATA_HOME` or fall back to the standard
  `~/.local/share` when the variable is not set."
  []
  (or (System/getenv "XDG_DATA_HOME")
      (str (System/getProperty "user.home") "/.local/share")))

;; client-id et client-secret dispo dans chezmoi.toml mais ce fichier n'est pas suivi.
(defn auth-file-path
  "Compute the path to the auth EDN file.
  Resolved on each call so callers can rebind `XDG_DATA_HOME`
  (or `with-redefs` this fn) in tests."
  []
  (str (xdg-data-home) "/google-drive-uploader/auth.edn"))

(defn read-auth-file
  "Read and parse the auth EDN file.
  Returns the parsed map, or `nil` if the file does not exist or is
  malformed. On parse error, logs a warning and returns `nil` so the
  exception never leaks to callers."
  ([] (read-auth-file (auth-file-path)))
  ([path]
   (let [f (io/file path)]
     (when (.exists f)
       (try
         (-> f slurp edn/read-string)
         (catch Throwable t
           (timbre/warn "Failed to parse auth file" path
                        ":" (.getMessage t))
           nil))))))

(defn save-auth-file!
  "Persist credentials to the auth EDN file, merging with the existing
  content (so partial updates — e.g. only `:access-token` — preserve the
  other keys). Creates parent directories if needed and pretty-prints
  the result for human readability.

  Returns the merged map that was written."
  ([data] (save-auth-file! (auth-file-path) data))
  ([path data]
   (let [f (io/file path)
         parent (.getParentFile f)
         existing (or (read-auth-file path) {})
         merged (merge existing data)]
     (when (and parent (not (.exists parent)))
       (.mkdirs parent))
     (with-open [w (io/writer f)]
       (pprint/pprint merged w))
     merged)))

(defn all-credentials-blank?
  "Return `true` if the four OAuth2 credential keys in `args` are all
  blank or missing.

  `:key-file` is intentionally NOT considered here: the EDN flow only
  covers OAuth2 user credentials. If the user gives only `--key-file`,
  this still returns `true` and the EDN file gets a chance to provide
  the OAuth2 keys."
  [{:keys [access-token refresh-token client-id client-secret]}]
  (and (str/blank? access-token)
       (str/blank? refresh-token)
       (str/blank? client-id)
       (str/blank? client-secret)))

(defn resolve-credentials
  "Enrich `args` with credentials from the auth.edn file when none of
  the four OAuth2 credentials is set in `args`.

  Behaviour:
  - At least one OAuth2 credential set in args -> `args` returned as-is.
  - All four blank AND auth.edn missing        -> `args` returned as-is.
  - All four blank AND auth.edn present        -> the four keys are
                                                  merged into `args`
                                                  from the EDN file.

  `:key-file` is never read or overwritten by this function."
  [args]
  (if (all-credentials-blank? args)
    (if-let [auth-data (read-auth-file)]
      (do
        (timbre/debug "resolve-credentials: loaded credentials from"
                      (auth-file-path))
        (merge args (select-keys auth-data
                                 [:access-token
                                  :refresh-token
                                  :client-id
                                  :client-secret])))
      (do
        (timbre/debug
         "resolve-credentials: no creds in args and no auth.edn at"
         (auth-file-path))
        args))
    args))

(defn update-access-token!
  "Persist a freshly-refreshed access token into auth.edn, but ONLY if
  the file already exists. No-op otherwise (we never auto-create the
  file — the user is expected to seed it manually the first time).

  Returns the merged map on success, or `nil` when the file is absent."
  [new-access-token]
  (let [path (auth-file-path)]
    (when (.exists (io/file path))
      (timbre/debug "update-access-token!: persisting new access token to"
                    path)
      (save-auth-file! path {:access-token new-access-token}))))
