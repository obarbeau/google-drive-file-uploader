(ns google-drive-file-uploader.core
  (:require [google-drive-file-uploader.auth :as auth]
            [google-drive-file-uploader.drive :as drive]
            [google-drive-file-uploader.logging :as logging]
            [taoensso.timbre :as timbre]
            [cli-matic.core :refer [run-cmd]]
            [failjure.core :as f]
            [google-drive-file-uploader.utils :as utils])
  (:gen-class))

(defn fail [msg]
  (println "[ERROR]" msg)
  (flush)
  (System/exit 1))

(defn success
  ([]
   (success "Upload completed successfully."))
  ([msg]
   (println msg)
   (flush)
   (System/exit 0)))

(defn mask-secret
  "Return a redacted version of a secret string, keeping only the last 4 chars
  for identification purposes. Returns nil/empty unchanged."
  [s]
  (cond
    (nil? s) nil
    (empty? s) s
    (< (count s) 8) "****"
    :else (str "****" (subs s (- (count s) 4)))))

(defn redact-args
  "Replace secret values in the args map with masked versions, so we can safely
  log the args without leaking credentials."
  [args]
  (-> args
      (update :access-token mask-secret)
      (update :refresh-token mask-secret)
      (update :client-secret mask-secret)))

(defn upload [args]
  ;; cli-matic has parsed args by now: re-apply verbose so that --no-verbose
  ;; correctly silences the early raw-arg detection from -main.
  (logging/set-verbose! (:verbose args))
  (timbre/debug "upload called with args:" (redact-args args))
  (let [args (auth/resolve-credentials args)]
    (timbre/debug "upload after resolve-credentials:" (redact-args args))
    (try
      (f/if-let-ok? [result (drive/upload-file-to-folder args)]
                    (success)
                    (fail (f/message result)))
      (catch Throwable t
        (timbre/debug "Uncaught exception in upload:" (.getMessage t))
        (.printStackTrace t)
        (fail (str "Uncaught exception: " (.getMessage t)))))))

(defn check-access-token [args]
  (logging/set-verbose! (:verbose args))
  (timbre/debug "check-access-token called with args:" (redact-args args))
  (let [args (auth/resolve-credentials args)]
    (timbre/debug "check-access-token after resolve-credentials:"
                  (redact-args args))
    (try
      (f/if-let-ok? [result (drive/check-access-token args)]
                    (success (str "Access token resolved: " (mask-secret result)))
                    (fail (f/message result)))
      (catch Throwable t
        (timbre/debug "Uncaught exception in check-access-token:" (.getMessage t))
        (.printStackTrace t)
        (fail (str "Uncaught exception: " (.getMessage t)))))))

(def ^:private verbose-opt
  "The --verbose / -v global flag. Declared in :global-opts so it must be
  placed BEFORE the subcommand (cli-matic does not allow global flags to
  appear after the subcommand). The subcommands' :description fields mention
  this constraint so it shows up in `uf --help`."
  {:as     (str "Print [DEBUG] logs to stderr. "
                "MUST be placed BEFORE the subcommand "
                "(e.g. `--verbose uf ...`). "
                "Use `--no-verbose` to force-disable.")
   :option "verbose"
   :short  "v"
   :type   :with-flag})

(def ^:private auth-sources-help
  "Help block describing the three credential sources, appended to the
  description of each command so it shows up in `uf --help` and
  `ct --help`."
  ["Authentication credential sources (priority order):"
   "  1. CLI args (--access-token, --refresh-token, --client-id, --client-secret)"
   "  2. environment variables (GD_ACCESS_TOKEN, GD_REFRESH_TOKEN,"
   "     GD_CLIENT_ID, GD_CLIENT_SECRET)"
   "  3. EDN file at $XDG_DATA_HOME/google-drive-uploader/auth.edn"
   "     (read ONLY when none of the four OAuth2 values is set"
   "     via CLI or env; falls back to ~/.local/share when"
   "     XDG_DATA_HOME is not defined)"
   ""
   "Expected auth.edn format:"
   "  {:client-id     \"...\""
   "   :client-secret \"...\""
   "   :refresh-token \"...\""
   "   :access-token  \"...\"}"
   ""
   "When auth.edn exists, refreshed access tokens are persisted back"
   "to it automatically (other keys are preserved)."
   ""
   "The service-account mode (--key-file / GD_KEY_FILE) is independent"
   "of auth.edn and is never read from or written to it."])

(def CONFIGURATION
  {:app      {:command     "google-drive-uploader"
              :description (str "Upload files to Google Drive from the command line. "
                                "Credentials can be provided via CLI args, "
                                "GD_* env vars, or "
                                "$XDG_DATA_HOME/google-drive-uploader/auth.edn "
                                "(see `uf --help` for details).")
              :version     "0.1"}
   :global-opts     [verbose-opt]
   :commands [{:command     "upload-file" :short "uf"
               :description (into ["Upload a file."
                                   ""
                                   "Tip: pass --verbose (or -v) BEFORE the subcommand"
                                   "to enable debug logging, e.g.:"
                                   "  google-drive-uploader --verbose uf --folder-id ..."
                                   ""]
                                  auth-sources-help)
               :opts        [{:option "folder" :short "f" :type :string :default ""}
                             {:option "folder-id" :short "fi" :type :string :default ""}
                             {:option "file-path" :short "fp" :type :string :default :present}
                             {:option "file-name" :short "fn" :type :string :default (utils/formatted-date-time)}
                             {:option "access-token" :short "at" :type :string :env "GD_ACCESS_TOKEN"}
                             {:option "key-file" :short "k" :type :string :env "GD_KEY_FILE"}
                             {:option "refresh-token" :short "rt" :type :string :env "GD_REFRESH_TOKEN"}
                             {:option "client-id" :short "ci" :type :string :env "GD_CLIENT_ID"}
                             {:option "client-secret" :short "cs" :type :string :env "GD_CLIENT_SECRET"}]
               :runs        upload}
              {:command     "check-token" :short "ct"
               :description (into ["Check access token and refresh if needed."
                                   ""
                                   "Tip: pass --verbose (or -v) BEFORE the subcommand"
                                   "to enable debug logging, e.g.:"
                                   "  google-drive-uploader --verbose ct --key-file ..."
                                   ""]
                                  auth-sources-help)
               :opts        [{:option "access-token" :short "at" :type :string :env "GD_ACCESS_TOKEN"}
                             {:option "key-file" :short "k" :type :string :env "GD_KEY_FILE"}
                             {:option "refresh-token" :short "rt" :type :string :env "GD_REFRESH_TOKEN"}
                             {:option "client-id" :short "ci" :type :string :env "GD_CLIENT_ID"}
                             {:option "client-secret" :short "cs" :type :string :env "GD_CLIENT_SECRET"}]
               :runs        check-access-token}]})

(defn -main
  "This is our entry point.
  Just pass parameters and configuration.
  Commands (functions) will be invoked as appropriate."
  [& args]
  ;; Pre-parse verbose detection: cli-matic hasn't run yet but we want early
  ;; debug logs (e.g. raw-args dump) to be visible when the user asked for
  ;; them. This is overridden later by upload/check-access-token once
  ;; cli-matic has done a full parse (so --no-verbose still wins).
  (logging/set-verbose! (logging/detect-verbose-in-raw-args args))
  (timbre/debug "-main called with raw args:" (vec args))
  (try
    (run-cmd args CONFIGURATION)
    (catch Throwable t
      (timbre/debug "Uncaught exception in -main:" (.getMessage t))
      (.printStackTrace t)
      (System/exit 2))
    (finally
      (flush)
      (binding [*out* *err*] (flush)))))
