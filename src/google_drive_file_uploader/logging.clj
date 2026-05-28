(ns google-drive-file-uploader.logging
  "Centralised Timbre configuration.

  Goals:
  - DEBUG/TRACE messages are hidden by default (min-level = :info).
  - When the user passes --verbose / -v, min-level drops to :debug.
  - All log output goes to stderr so it never gets mixed up with the regular
    [ERROR] / success messages that the program prints on stdout.
  - Output format is minimal — just `[LEVEL] message` — so logs stay readable
    on a CLI and don't clutter the screen with timestamps and namespaces.
  - This setup is applied as a side-effect on namespace load (see bottom of
    file) so callers only need to require the namespace once."
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]))

(defn- minimal-output-fn
  "Render `[LEVEL] message` with no timestamp, hostname or namespace.
  `data` is the map Timbre passes to output-fns; `:msg_` is a delay holding
  the joined message arguments."
  [data]
  (let [{:keys [level msg_]} data]
    (str "[" (str/upper-case (name level)) "] " (force msg_))))

(def base-config
  "Timbre configuration: minimal stderr output, threshold defaults to :info
  (so :debug and :trace are silenced unless `set-verbose!` raises the level)."
  {:min-level :info
   :output-fn minimal-output-fn
   :appenders {:println (assoc (appenders/println-appender {:stream :*err*})
                               :output-fn minimal-output-fn)}})

(defn set-verbose!
  "Switch debug logs ON or OFF at runtime.
  Called twice during a normal run: once early from `-main` based on raw args
  (so even very early debug calls work), then again after cli-matic has parsed
  the args, in case the user used --no-verbose to explicitly disable."
  [verbose?]
  (timbre/set-min-level! (if verbose? :debug :info)))

(defn detect-verbose-in-raw-args
  "Cheap pre-cli-matic scan of the raw argv. Returns true iff `-v` or
  `--verbose` appears literally — does not match `--no-verbose`."
  [raw-args]
  (boolean (some #{"-v" "--verbose"} raw-args)))

;; Apply the base config eagerly when this namespace is loaded.
(timbre/set-config! base-config)
