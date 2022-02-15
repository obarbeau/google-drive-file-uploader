(ns google-drive-file-uploader.drive
  (:require [failjure.core :as f]
            [google-drive-file-uploader.config :as config]
            [clj-http.client :as http]
            [jsonista.core :as json]
            [google-drive-file-uploader.utils :as utils])
  (:import (com.google.api.client.googleapis.auth.oauth2 GoogleCredential)
           (java.io FileInputStream)))

(defn get-access-token-from-key-file
  "Exchange a service-account JSON key file for a short-lived Drive access token.
  Performs the JWT-Bearer flow (RFC 7523) via the Google API Client SDK."
  [^String filename]
  (.getAccessToken
   (doto
    (.createScoped
     (GoogleCredential/fromStream
      (FileInputStream. filename))
     ["https://www.googleapis.com/auth/drive"])
     (.refreshToken))))

(def mapper
  (json/object-mapper
   {:encode-key-fn utils/snake-case-keyword-keys
    :decode-key-fn utils/kebab-caseize-keyword}))

(defn folder? [{mime-type :mime-type}]
  (= "application/vnd.google-apps.folder" mime-type))

(defn get-folders [access-token]
  (let [url (config/get-files-url)
        url-q (str url "?q=mimeType='application/vnd.google-apps.folder'")
        {:keys [status body] :as response} (http/get url-q {:headers          {"Authorization" (str "Bearer " access-token)}
                                                            :throw-exceptions false})]
    (condp = status
      200 (-> body
              (json/read-value mapper))
      response)))

(defn upload-file-multipart
  ([folder-hierarchy file-path access-token verbose]
   (upload-file-multipart folder-hierarchy file-path (utils/formatted-date-time) access-token verbose))
  ([folder-hierarchy file-path file-name access-token verbose]
   (when verbose (println "Uploading file."))
   (let [url               (-> (config/file-upload-url)
                               (str "?uploadType=multipart"))
         parents           (clojure.string/split folder-hierarchy #"/")
         multipart-content [{:name      "metadata"
                             :content   (-> {:name    file-name
                                             :parents parents}
                                            json/write-value-as-string)
                             :mime-type "application/json"
                             :encoding  "UTF-8"}
                            {:name      "file"
                             :content   (clojure.java.io/file file-path)
                             :mime-type "application/vnd.android.package-archive"
                             :encoding  "UTF-8"}]
         {:keys [status body] :as response} (http/post url {:headers          {"Authorization" (str "Bearer " access-token)}
                                                            :multipart        multipart-content
                                                            :throw-exceptions false})]
     (when verbose (println response))
     (condp = status
       200 true
       false))))

(defn authorization-token [refresh-token client-id client-secret verbose]
  (when verbose (println "Getting new authorization token."))
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
        token (condp = status
                200 (-> body
                        json/read-value
                        utils/kebab-caseize-keys
                        :access-token)
                (throw (ex-info (str "Error retrieving authorization-token" {:status status
                                                                             :body   body}) {})))]
    (when verbose (println "Write authorization token to file."))
    (println "Please update $XDG_CONFIG_HOME/chezmoi/chezmoi.toml > google_drive_uploader.access.token with this AT\n" token)
    #_(spit (str (System/getProperty "user.home") "/.google-drive-access-token") token)
    token))

(defn valid-access-token? [access-token verbose]
  (when verbose (println "Checking validity of access token."))
  (let [url (str (config/validate-access-token-url)
                 access-token)
        {status :status} (http/post url {:throw-exceptions false})]
    (when (= 200 status)
      (when verbose (println "Access token is valid."))
      true)))

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
           key-file
           verbose]}]
  (cond
    (valid-access-token? access-token verbose)
    access-token

    (and refresh-token client-id client-secret)
    (authorization-token refresh-token client-id client-secret verbose)

    key-file
    (do
      (when verbose (println "Getting access token from service-account key file."))
      (get-access-token-from-key-file key-file))))

(defn validate [{:keys [access-token refresh-token client-id client-secret key-file]} & _]
  (cond
    (and (empty? access-token)
         (empty? key-file)
         (or (empty? refresh-token)
             (empty? client-id)
             (empty? client-secret)))
    (f/fail (str "Either Access Token, or Key File, "
                 "or Refresh Token + Client Id + Client Secret must be given"))
    :else nil))

(defn lookup-folder-id-by-name
  "Search the Drive folders accessible to the access token,
  return the id of the first one whose name matches `folder-name` (after trim)."
  [access-token folder-name]
  (let [trimmed (clojure.string/trim folder-name)]
    (->> (get-folders access-token)
         :files
         (some (fn [{name :name :as e}]
                 (when (= trimmed name) e)))
         :id)))

(defn upload-file-to-folder
  "Upload a file to a Google Drive folder.

  Authentication (in this order of priority):
  - `access-token` if still valid,
  - `refresh-token` + `client-id` + `client-secret` (OAuth2 user flow),
  - `key-file` pointing to a service-account JSON key.

  Folder targeting:
  - `folder-id` if provided is used directly (recommended for service accounts),
  - otherwise `folder` is treated as a folder name to look up in the user's Drive."
  [{:keys [folder
           folder-id
           file-path
           file-name
           verbose] :as args}]
  (f/try-all [_                (validate args)
              access-token     (check-access-token
                                (select-keys args [:access-token
                                                   :refresh-token
                                                   :client-id
                                                   :client-secret
                                                   :key-file
                                                   :verbose]))
              upload-folder-id (or folder-id
                                   (when-not (clojure.string/blank? folder)
                                     (lookup-folder-id-by-name access-token folder)))]
             (if (nil? upload-folder-id)
               (f/fail (format "Folder %s does not exist" (or folder-id folder)))
               (upload-file-multipart upload-folder-id file-path file-name access-token verbose))))

(comment
  ;; Service-account flow (recommended for automation):
  (upload-file-to-folder
   {:file-path "project.clj"
    :file-name "project.clj"
    :key-file "/path/to/service-account.json"
    :folder-id "1A2B3C..."})

  ;; OAuth2 user flow with a still-valid access token:
  (upload-file-to-folder
   {:file-path "project.clj"
    :file-name "project.clj"
    :access-token "ya29...."
    :folder "MyFolder"})
  ;
  )
