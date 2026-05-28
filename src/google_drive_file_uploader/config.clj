(ns google-drive-file-uploader.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def config (-> (io/resource "config.edn")
                slurp
                edn/read-string))

(defn file-upload-url []
  (:file-upload-url config))

(defn new-access-token-url []
  (:new-access-token-url config))

(defn get-files-url []
  (:get-files-url config))

(defn validate-access-token-url []
  (:access-token-validation-url config))
