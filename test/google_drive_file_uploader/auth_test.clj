(ns google-drive-file-uploader.auth-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [google-drive-file-uploader.auth :as auth]))

;; ----------------------------------------------------------------------------
;; Test fixtures: each test gets its own temporary auth.edn path,
;; rebound via `with-redefs` so we never touch the real
;; `$XDG_DATA_HOME/google-drive-uploader/auth.edn`.
;; ----------------------------------------------------------------------------

(def ^:dynamic *tmp-auth-path* nil)

(defn make-tmp-path
  "Allocate a unique non-existing path inside the JVM's tmp dir.
  We don't create the file: the tested code is responsible for that."
  []
  (let [base (System/getProperty "java.io.tmpdir")
        f (java.io.File/createTempFile "auth-test-" ".edn"
                                       (io/file base))]
    ;; Delete it right away so tests start with a clean slate; the
    ;; tested function will recreate it as needed.
    (.delete f)
    (.getPath f)))

(defn with-tmp-auth-path-fixture
  "Each test gets a fresh temp path and we wipe it after."
  [f]
  (let [path (make-tmp-path)]
    (binding [*tmp-auth-path* path]
      (try
        (with-redefs [auth/auth-file-path (constantly path)]
          (f))
        (finally
          (let [file (io/file path)]
            (when (.exists file)
              (.delete file))))))))

(use-fixtures :each with-tmp-auth-path-fixture)

;; ----------------------------------------------------------------------------
;; xdg-data-home / auth-file-path
;; ----------------------------------------------------------------------------

(deftest xdg-data-home-test
  (testing "Falls back to ~/.local/share when XDG_DATA_HOME is not set"
    (with-redefs [auth/xdg-data-home
                  (fn []
                    (or (System/getenv "XDG_DATA_HOME")
                        (str (System/getProperty "user.home")
                             "/.local/share")))]
      ;; The function should always return a non-blank string.
      (is (seq (auth/xdg-data-home))))))

;; ----------------------------------------------------------------------------
;; read-auth-file
;; ----------------------------------------------------------------------------

(deftest read-auth-file-test
  (testing "Returns nil when the file does not exist"
    (is (nil? (auth/read-auth-file *tmp-auth-path*))))

  (testing "Returns the parsed map when the file is valid EDN"
    (spit *tmp-auth-path*
          (pr-str {:client-id     "abc"
                   :client-secret "def"
                   :refresh-token "ghi"
                   :access-token  "jkl"}))
    (is (= {:client-id     "abc"
            :client-secret "def"
            :refresh-token "ghi"
            :access-token  "jkl"}
           (auth/read-auth-file *tmp-auth-path*))))

  (testing "Returns nil and does not throw when the file is malformed"
    (spit *tmp-auth-path* "{:incomplete map without close")
    (is (nil? (auth/read-auth-file *tmp-auth-path*)))))

;; ----------------------------------------------------------------------------
;; save-auth-file!
;; ----------------------------------------------------------------------------

(deftest save-auth-file-test
  (testing "Creates the file when it does not exist"
    (auth/save-auth-file! *tmp-auth-path*
                          {:client-id     "abc"
                           :client-secret "def"})
    (is (.exists (io/file *tmp-auth-path*)))
    (is (= {:client-id     "abc"
            :client-secret "def"}
           (-> *tmp-auth-path* slurp edn/read-string))))

  (testing "Merges with existing content (partial updates preserved)"
    (spit *tmp-auth-path*
          (pr-str {:client-id     "abc"
                   :client-secret "def"
                   :refresh-token "old-refresh"
                   :access-token  "old-access"}))
    (auth/save-auth-file! *tmp-auth-path*
                          {:access-token "new-access"})
    (is (= {:client-id     "abc"
            :client-secret "def"
            :refresh-token "old-refresh"
            :access-token  "new-access"}
           (-> *tmp-auth-path* slurp edn/read-string))))

  (testing "Creates parent directory when needed"
    (let [nested-path (str (System/getProperty "java.io.tmpdir")
                           "/auth-test-nested-"
                           (System/currentTimeMillis)
                           "/sub/auth.edn")]
      (try
        (auth/save-auth-file! nested-path {:client-id "abc"})
        (is (.exists (io/file nested-path)))
        (finally
          (let [f (io/file nested-path)]
            (.delete f)
            (.delete (.getParentFile f))
            (.delete (.getParentFile (.getParentFile f)))))))))

;; ----------------------------------------------------------------------------
;; all-credentials-blank?
;; ----------------------------------------------------------------------------

(deftest all-credentials-blank-test
  (testing "True when all four OAuth2 keys are missing"
    (is (auth/all-credentials-blank? {})))

  (testing "True when all four OAuth2 keys are blank strings"
    (is (auth/all-credentials-blank? {:access-token  ""
                                      :refresh-token ""
                                      :client-id     ""
                                      :client-secret ""})))

  (testing "True even when key-file is set (key-file is not considered)"
    (is (auth/all-credentials-blank? {:key-file "/path/to/sa.json"})))

  (testing "False when access-token is set"
    (is (not (auth/all-credentials-blank? {:access-token "abc"}))))

  (testing "False when refresh-token is set"
    (is (not (auth/all-credentials-blank? {:refresh-token "abc"}))))

  (testing "False when client-id is set"
    (is (not (auth/all-credentials-blank? {:client-id "abc"}))))

  (testing "False when client-secret is set"
    (is (not (auth/all-credentials-blank? {:client-secret "abc"})))))

;; ----------------------------------------------------------------------------
;; resolve-credentials
;; ----------------------------------------------------------------------------

(deftest resolve-credentials-test
  (testing "Returns args unchanged when at least one credential is set"
    (let [args {:access-token "from-env" :folder "F"}]
      (is (= args (auth/resolve-credentials args)))))

  (testing "Returns args unchanged when all blank but file does not exist"
    (let [args {:folder "F"}]
      (is (= args (auth/resolve-credentials args)))))

  (testing "Merges credentials from auth.edn when all four are blank"
    (spit *tmp-auth-path*
          (pr-str {:client-id     "edn-cid"
                   :client-secret "edn-cs"
                   :refresh-token "edn-rt"
                   :access-token  "edn-at"}))
    (let [enriched (auth/resolve-credentials {:folder "F"})]
      (is (= "edn-cid" (:client-id enriched)))
      (is (= "edn-cs" (:client-secret enriched)))
      (is (= "edn-rt" (:refresh-token enriched)))
      (is (= "edn-at" (:access-token enriched)))
      (is (= "F" (:folder enriched)) "non-credential keys preserved")))

  (testing "key-file in args does NOT bypass auth.edn lookup"
    (spit *tmp-auth-path*
          (pr-str {:client-id     "edn-cid"
                   :client-secret "edn-cs"
                   :refresh-token "edn-rt"
                   :access-token  "edn-at"}))
    (let [enriched (auth/resolve-credentials
                    {:key-file "/path/to/sa.json"})]
      (is (= "edn-cid" (:client-id enriched))
          "key-file alone leaves OAuth2 keys blank, so EDN is read")
      (is (= "/path/to/sa.json" (:key-file enriched))
          "key-file is preserved untouched")))

  (testing "auth.edn does NOT override credentials already set in args"
    (spit *tmp-auth-path*
          (pr-str {:client-id     "edn-cid"
                   :client-secret "edn-cs"
                   :refresh-token "edn-rt"
                   :access-token  "edn-at"}))
    (let [args {:access-token "env-at" :folder "F"}
          result (auth/resolve-credentials args)]
      (is (= args result)
          "one credential set in args -> EDN is not even read"))))

;; ----------------------------------------------------------------------------
;; update-access-token!
;; ----------------------------------------------------------------------------

(deftest update-access-token-test
  (testing "Returns nil and does nothing when auth.edn does not exist"
    (is (nil? (auth/update-access-token! "new-token")))
    (is (not (.exists (io/file *tmp-auth-path*)))
        "the file must not be auto-created"))

  (testing "Updates only :access-token when auth.edn exists"
    (spit *tmp-auth-path*
          (pr-str {:client-id     "abc"
                   :client-secret "def"
                   :refresh-token "ghi"
                   :access-token  "old-at"}))
    (let [result (auth/update-access-token! "new-at")]
      (is (= "new-at" (:access-token result)))
      (is (= "abc" (:client-id result)) "other keys untouched")
      (is (= "def" (:client-secret result)))
      (is (= "ghi" (:refresh-token result)))
      (is (= {:client-id     "abc"
              :client-secret "def"
              :refresh-token "ghi"
              :access-token  "new-at"}
             (-> *tmp-auth-path* slurp edn/read-string))))))
