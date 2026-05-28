(ns google-drive-file-uploader.drive
  (:require [failjure.core :as f]
            [google-drive-file-uploader.config :as config]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [taoensso.timbre :as timbre]
            [google-drive-file-uploader.utils :as utils])
  (:import (com.google.api.client.googleapis.auth.oauth2 GoogleCredential)
           (java.io FileInputStream)))

(defn get-access-token-from-key-file
  "Exchange a service-account JSON key file for a short-lived Drive access token.
  Performs the JWT-Bearer flow (RFC 7523) via the Google API Client SDK."
  [^String filename]
  (timbre/debug "get-access-token-from-key-file: filename =" filename)
  (timbre/debug "get-access-token-from-key-file: file exists?"
                (.exists (java.io.File. filename)))
  (try
    (let [credential (-> (FileInputStream. filename)
                         (GoogleCredential/fromStream)
                         (.createScoped ["https://www.googleapis.com/auth/drive"]))]
      (timbre/debug "get-access-token-from-key-file: credential created, calling refreshToken")
      (.refreshToken credential)
      (let [token (.getAccessToken credential)]
        (timbre/debug "get-access-token-from-key-file: token obtained, length ="
                      (when token (count token)))
        token))
    (catch Throwable t
      (timbre/debug "get-access-token-from-key-file: EXCEPTION:" (.getMessage t))
      (throw t))))

(def mapper
  (json/object-mapper
   {:encode-key-fn utils/snake-case-keyword-keys
    :decode-key-fn utils/kebab-caseize-keyword}))

(defn folder? [{mime-type :mime-type}]
  (= "application/vnd.google-apps.folder" mime-type))

(defn get-folders [access-token]
  (timbre/debug "get-folders called")
  (let [url (config/get-files-url)
        url-q (str url "?q=mimeType='application/vnd.google-apps.folder'")
        _ (timbre/debug "get-folders: GET" url-q)
        {:keys [status body] :as response}
        (http/get url-q {:headers          {"Authorization" (str "Bearer " access-token)}
                         :throw-exceptions false})]
    (timbre/debug "get-folders: status =" status)
    (condp = status
      200 (-> body
              (json/read-value mapper))
      (do
        (timbre/debug "get-folders: non-200 body =" body)
        response))))

(defn upload-file-multipart
  ([folder-hierarchy file-path access-token]
   (upload-file-multipart folder-hierarchy file-path (utils/formatted-date-time) access-token))
  ([folder-hierarchy file-path file-name access-token]
   (timbre/debug "upload-file-multipart called: folder-hierarchy =" folder-hierarchy
                 "file-path =" file-path
                 "file-name =" file-name)
   (timbre/info "Uploading file.")
   (let [file (io/file file-path)
         _ (timbre/debug "upload-file-multipart: file exists?" (.exists file)
                         "size =" (when (.exists file) (.length file)))
         url               (-> (config/file-upload-url)
                               (str "?uploadType=multipart"))
         parents           (str/split folder-hierarchy #"/")
         _ (timbre/debug "upload-file-multipart: POST" url "parents =" (vec parents))
         multipart-content [{:name      "metadata"
                             :content   (-> {:name    file-name
                                             :parents parents}
                                            json/write-value-as-string)
                             :mime-type "application/json"
                             :encoding  "UTF-8"}
                            {:name      "file"
                             :content   file
                             :mime-type "application/vnd.android.package-archive"
                             :encoding  "UTF-8"}]
         {:keys [status body] :as response}
         (http/post url {:headers          {"Authorization" (str "Bearer " access-token)}
                         :multipart        multipart-content
                         :throw-exceptions false})]
     (timbre/debug "upload-file-multipart: status =" status)
     (when (not= 200 status)
       (timbre/debug "upload-file-multipart: non-200 body =" body))
     (timbre/debug "upload-file-multipart: full response =" response)
     (condp = status
       200 true
       false))))

(defn authorization-token [refresh-token client-id client-secret]
  (timbre/debug "authorization-token called")
  (timbre/info "Getting new authorization token.")
  (let [url (config/new-access-token-url)
        body (-> {:client-id     client-id
                  :client-secret client-secret
                  :grant-type    "refresh_token"
                  :refresh-token refresh-token}
                 utils/snake-case-keyword-keys
                 json/write-value-as-string)
        {:keys [status body]} (http/post url {:body             body
                                              :content-type     :json
                                              :throw-exceptions false})
        _ (timbre/debug "authorization-token: status =" status)
        token (condp = status
                200 (-> body
                        json/read-value
                        utils/kebab-caseize-keys
                        :access-token)
                (throw (ex-info (str "Error retrieving authorization-token" {:status status
                                                                             :body   body}) {})))]
    (timbre/debug "authorization-token: writing token to stdout (legacy behaviour)")
    (println "Please update $XDG_CONFIG_HOME/chezmoi/chezmoi.toml > google_drive_uploader.access.token with this AT\n" token)
    #_(spit (str (System/getProperty "user.home") "/.google-drive-access-token") token)
    token))

(defn valid-access-token? [access-token]
  (timbre/debug "valid-access-token? called: access-token blank?"
                (str/blank? access-token))
  (if (str/blank? access-token)
    (do (timbre/debug "valid-access-token?: access-token is blank, skipping HTTP call")
        false)
    (let [url (str (config/validate-access-token-url)
                   access-token)
          {status :status} (http/post url {:throw-exceptions false})]
      (timbre/debug "valid-access-token?: status =" status)
      (when (= 200 status)
        (timbre/debug "valid-access-token?: access token is valid")
        true))))

(defn check-access-token
  "Resolve the access token to use, in priority order:
  1. an already-valid access-token,
  2. a fresh token obtained from refresh-token + client credentials,
  3. a fresh token obtained from a service-account key file.
  Returns the access token string, or nil if nothing usable was provided
  (validate should have caught that case earlier)."
  [{:keys [access-token
           refresh-token
           client-id
           client-secret
           key-file]}]
  (timbre/debug "check-access-token: access-token blank?"
                (str/blank? access-token)
                "refresh-token blank?" (str/blank? refresh-token)
                "client-id blank?" (str/blank? client-id)
                "client-secret blank?" (str/blank? client-secret)
                "key-file blank?" (str/blank? key-file))
  (cond
    (valid-access-token? access-token)
    (do (timbre/debug "check-access-token: branch = existing access-token (valid)")
        access-token)

    (and refresh-token client-id client-secret)
    (do (timbre/debug "check-access-token: branch = refresh-token flow")
        (authorization-token refresh-token client-id client-secret))

    key-file
    (do
      (timbre/debug "check-access-token: branch = key-file (service-account)")
      (timbre/info "Getting access token from service-account key file.")
      (get-access-token-from-key-file key-file))

    :else
    (do (timbre/debug "check-access-token: NO branch matched - returning nil")
        nil)))

(defn validate [{:keys [access-token refresh-token client-id client-secret key-file]} & _]
  (timbre/debug "validate called")
  (let [missing-everything?
        (and (empty? access-token)
             (empty? key-file)
             (or (empty? refresh-token)
                 (empty? client-id)
                 (empty? client-secret)))]
    (timbre/debug "validate: missing-everything? =" missing-everything?)
    (if missing-everything?
      (f/fail (str "Either Access Token, or Key File, "
                   "or Refresh Token + Client Id + Client Secret must be given"))
      nil)))

(defn lookup-folder-id-by-name
  "Search the Drive folders accessible to the access token,
  return the id of the first one whose name matches `folder-name` (after trim)."
  [access-token folder-name]
  (timbre/debug "lookup-folder-id-by-name called: folder-name =" folder-name)
  (let [trimmed (str/trim folder-name)
        result (->> (get-folders access-token)
                    :files
                    (some (fn [{name :name :as e}]
                            (when (= trimmed name) e)))
                    :id)]
    (timbre/debug "lookup-folder-id-by-name: result =" result)
    result))

(defn upload-file-to-folder
  "Upload a file to a Google Drive folder.

  Authentication (in this order of priority):
  - `access-token` if still valid,
  - `refresh-token` + `client-id` + `client-secret` (OAuth2 user flow),
  - `key-file` pointing to a service-account JSON key.

  IMPORTANT: the service-account flow only works for Google Workspace accounts
  uploading to a Shared Drive. Personal Google accounts (gmail.com) MUST use
  the OAuth2 user flow because service accounts don't have personal Drive
  storage quota and Shared Drives are a Workspace-only feature.

  Folder targeting:
  - `folder-id` if provided is used directly (recommended for service accounts),
  - otherwise `folder` is treated as a folder name to look up in the user's Drive."
  [{:keys [folder
           folder-id
           file-path
           file-name] :as args}]
  (timbre/debug "upload-file-to-folder called")
  (timbre/debug "upload-file-to-folder: folder =" (pr-str folder)
                "folder-id =" (pr-str folder-id)
                "file-path =" (pr-str file-path)
                "file-name =" (pr-str file-name))
  (let [result
        (f/try-all [_                (validate args)
                    access-token     (check-access-token
                                      (select-keys args [:access-token
                                                         :refresh-token
                                                         :client-id
                                                         :client-secret
                                                         :key-file]))
                    upload-folder-id (or folder-id
                                         (when-not (str/blank? folder)
                                           (lookup-folder-id-by-name access-token folder)))]
                   (do
                     (timbre/debug "upload-file-to-folder: access-token resolved? ="
                                   (some? access-token))
                     (timbre/debug "upload-file-to-folder: upload-folder-id ="
                                   (pr-str upload-folder-id))
                     (if (str/blank? upload-folder-id)
                       (do (timbre/debug "upload-file-to-folder: folder not found, returning failure")
                           (f/fail (format "Folder %s does not exist" (or folder-id folder))))
                       (do (timbre/debug "upload-file-to-folder: calling upload-file-multipart")
                           (upload-file-multipart upload-folder-id file-path file-name access-token)))))]
    (timbre/debug "upload-file-to-folder: final result =" result)
    result))

(comment
  ;; Service-account flow (Google Workspace + Shared Drive uniquement, ne
  ;; fonctionne PAS avec un compte Google personnel car les service accounts
  ;; n'ont pas de quota de stockage Drive personnel) :
  (upload-file-to-folder
   {:file-path "project.clj"
    :file-name "project.clj"
    :key-file "/path/to/service-account.json"
    :folder-id "1A2B3C..."})

  ;; OAuth2 user flow with a still-valid access token (seul mode utilisable
  ;; pour un compte Google personnel gmail.com) :
  (upload-file-to-folder
   {:file-path "project.clj"
    :file-name "project.clj"
    :access-token "ya29...."
    :folder "MyFolder"})
  ;
  )
