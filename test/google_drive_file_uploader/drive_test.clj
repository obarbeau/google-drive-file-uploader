(ns google-drive-file-uploader.drive-test
  (:require [clojure.test :refer :all]
            [google-drive-file-uploader.drive :as drive]
            [mock-clj.core :as m]
            [failjure.core :as f]))

(def folder-list-stub
  {:files [{:id        "foo-id-1"
            :name      "foo-folder"
            :mime-type "application/vnd.google-apps.folder"}]})

(deftest upload-file-to-folder-oauth2-test
  (testing "OAuth2 flow: should check the validity of access token before calling get-folders"
    (m/with-mock [drive/valid-access-token?   true
                  drive/get-folders           folder-list-stub
                  drive/upload-file-multipart true]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder        "foo-folder"
                                                        :file-path     "/users/foo/a.apk"
                                                        :file-name     "foo-name.apk"
                                                        :access-token  "foo-access-token"
                                                        :refresh-token "foo-refresh-token"
                                                        :client-id     "foo-client-id"
                                                        :client-secret "foo-client-secret"}))))
      (is (= 1 (m/call-count #'drive/valid-access-token?)))
      (is (= 1 (m/call-count #'drive/get-folders)))
      (is (= 1 (m/call-count #'drive/upload-file-multipart)))
      (is (= ["foo-access-token"]
             (m/last-call #'drive/valid-access-token?)))
      (is (= ["foo-id-1" "/users/foo/a.apk" "foo-name.apk" "foo-access-token"]
             (m/last-call #'drive/upload-file-multipart)))))

  (testing "OAuth2 flow: should fetch a new access token if the supplied access-token is not valid"
    (m/with-mock [drive/valid-access-token?   false
                  drive/get-folders           folder-list-stub
                  drive/upload-file-multipart true
                  drive/authorization-token   "new-access-token"]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder        "foo-folder"
                                                        :file-path     "/users/foo/a.apk"
                                                        :file-name     "foo-name.apk"
                                                        :access-token  "foo-access-token"
                                                        :refresh-token "foo-refresh-token"
                                                        :client-id     "foo-client-id"
                                                        :client-secret "foo-client-secret"}))))
      (is (= 1 (m/call-count #'drive/valid-access-token?)))
      (is (= 1 (m/call-count #'drive/get-folders)))
      (is (= 1 (m/call-count #'drive/upload-file-multipart)))
      (is (= 1 (m/call-count #'drive/authorization-token)))
      (is (= ["foo-access-token"]
             (m/last-call #'drive/valid-access-token?)))
      (is (= ["foo-refresh-token" "foo-client-id" "foo-client-secret"]
             (m/last-call #'drive/authorization-token)))
      (is (= ["foo-id-1" "/users/foo/a.apk" "foo-name.apk" "new-access-token"]
             (m/last-call #'drive/upload-file-multipart))))))

(deftest upload-file-to-folder-service-account-test
  (testing "Service-account flow: should derive an access token from the key-file"
    (m/with-mock [drive/valid-access-token?            false
                  drive/get-access-token-from-key-file "sa-access-token"
                  drive/get-folders                    folder-list-stub
                  drive/upload-file-multipart          true]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder    "foo-folder"
                                                        :file-path "/users/foo/a.apk"
                                                        :file-name "foo-name.apk"
                                                        :key-file  "/path/to/sa.json"}))))
      (is (= 1 (m/call-count #'drive/get-access-token-from-key-file)))
      (is (= 1 (m/call-count #'drive/upload-file-multipart)))
      (is (= ["/path/to/sa.json"]
             (m/last-call #'drive/get-access-token-from-key-file)))
      (is (= ["foo-id-1" "/users/foo/a.apk" "foo-name.apk" "sa-access-token"]
             (m/last-call #'drive/upload-file-multipart)))))

  (testing "OAuth2 priority: a valid access-token must be preferred over a key-file"
    (m/with-mock [drive/valid-access-token?            true
                  drive/get-access-token-from-key-file "sa-access-token"
                  drive/get-folders                    folder-list-stub
                  drive/upload-file-multipart          true]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder       "foo-folder"
                                                        :file-path    "/users/foo/a.apk"
                                                        :file-name    "foo-name.apk"
                                                        :access-token "foo-access-token"
                                                        :key-file     "/path/to/sa.json"}))))
      (is (= 0 (m/call-count #'drive/get-access-token-from-key-file))
          "key-file must not be consulted when access-token is valid")
      (is (= ["foo-id-1" "/users/foo/a.apk" "foo-name.apk" "foo-access-token"]
             (m/last-call #'drive/upload-file-multipart))))))

(deftest upload-file-to-folder-folder-id-test
  (testing "Folder-id flow: should bypass folder-name lookup when folder-id is given"
    (m/with-mock [drive/valid-access-token?            false
                  drive/get-access-token-from-key-file "sa-access-token"
                  drive/get-folders                    folder-list-stub
                  drive/upload-file-multipart          true]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder-id "explicit-folder-id"
                                                        :file-path "/users/foo/a.apk"
                                                        :file-name "foo-name.apk"
                                                        :key-file  "/path/to/sa.json"}))))
      (is (= 0 (m/call-count #'drive/get-folders))
          "get-folders must not be called when folder-id is provided")
      (is (= ["explicit-folder-id" "/users/foo/a.apk" "foo-name.apk" "sa-access-token"]
             (m/last-call #'drive/upload-file-multipart)))))

  (testing "Folder-id flow: a folder-id wins over a folder-name lookup"
    (m/with-mock [drive/valid-access-token?   true
                  drive/get-folders           folder-list-stub
                  drive/upload-file-multipart true]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder       "foo-folder"
                                                        :folder-id    "explicit-folder-id"
                                                        :file-path    "/users/foo/a.apk"
                                                        :file-name    "foo-name.apk"
                                                        :access-token "foo-access-token"}))))
      (is (= 0 (m/call-count #'drive/get-folders)))
      (is (= ["explicit-folder-id" "/users/foo/a.apk" "foo-name.apk" "foo-access-token"]
             (m/last-call #'drive/upload-file-multipart))))))

(deftest upload-file-to-folder-validation-test
  (testing "Should fail when access-token, key-file and refresh-token are all missing"
    (m/with-mock [drive/valid-access-token?   true
                  drive/get-folders           folder-list-stub
                  drive/upload-file-multipart true
                  drive/authorization-token   "new-access-token"]
      (is (f/failed? (drive/upload-file-to-folder {:folder        "foo-folder"
                                                   :file-path     "/users/foo/a.apk"
                                                   :file-name     "foo-name.apk"
                                                   :client-id     "foo-client-id"
                                                   :client-secret "foo-client-secret"})))
      (is (= 0 (m/call-count #'drive/valid-access-token?)))
      (is (= 0 (m/call-count #'drive/get-folders)))
      (is (= 0 (m/call-count #'drive/upload-file-multipart)))
      (is (= 0 (m/call-count #'drive/authorization-token)))))

  (testing "Should fail when access-token, key-file and client-id are missing"
    (m/with-mock [drive/valid-access-token?   true
                  drive/get-folders           folder-list-stub
                  drive/upload-file-multipart true
                  drive/authorization-token   "new-access-token"]
      (is (f/failed? (drive/upload-file-to-folder {:folder        "foo-folder"
                                                   :file-path     "/users/foo/a.apk"
                                                   :file-name     "foo-name.apk"
                                                   :refresh-token "foo-refresh-token"
                                                   :client-secret "foo-client-secret"})))
      (is (= 0 (m/call-count #'drive/valid-access-token?)))
      (is (= 0 (m/call-count #'drive/get-folders)))
      (is (= 0 (m/call-count #'drive/upload-file-multipart)))
      (is (= 0 (m/call-count #'drive/authorization-token)))))

  (testing "Should fail when access-token, key-file and client-secret are missing"
    (m/with-mock [drive/valid-access-token?   true
                  drive/get-folders           folder-list-stub
                  drive/upload-file-multipart true
                  drive/authorization-token   "new-access-token"]
      (is (f/failed? (drive/upload-file-to-folder {:folder        "foo-folder"
                                                   :file-path     "/users/foo/a.apk"
                                                   :file-name     "foo-name.apk"
                                                   :refresh-token "foo-refresh-token"
                                                   :client-id     "foo-client-id"})))
      (is (= 0 (m/call-count #'drive/valid-access-token?)))
      (is (= 0 (m/call-count #'drive/get-folders)))
      (is (= 0 (m/call-count #'drive/upload-file-multipart)))
      (is (= 0 (m/call-count #'drive/authorization-token)))))

  (testing "Should pass validation when only key-file is given (no OAuth2 creds at all)"
    (m/with-mock [drive/valid-access-token?            false
                  drive/get-access-token-from-key-file "sa-access-token"
                  drive/get-folders                    folder-list-stub
                  drive/upload-file-multipart          true]
      (is (not (f/failed? (drive/upload-file-to-folder {:folder    "foo-folder"
                                                        :file-path "/users/foo/a.apk"
                                                        :file-name "foo-name.apk"
                                                        :key-file  "/path/to/sa.json"}))))
      (is (= 1 (m/call-count #'drive/upload-file-multipart))))))
