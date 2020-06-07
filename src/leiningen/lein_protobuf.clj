(ns leiningen.lein-protobuf
  (:require [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [clojure.string :as strings]
            [clojure.java.shell :as sh])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn print-err [& s]
  (binding [*out* *err*]
    (apply println s)))

(defn parse-dependency [[dep-path rev & opts]]
  (if-not (even? (count opts))
    (throw (IllegalArgumentException. "dependency opts should be key-value pairs"))
    (let [opts-map (apply hash-map opts)]
      (merge opts-map {:dep-path (str dep-path) :rev rev}))))

(defn parse-config [config]
  (->> config
       :dependencies
       (map parse-dependency)))

(defn append-dir [parent & children]
  (strings/join File/separator (concat [parent] children)))

(defn checkout! [git-repo tag-or-sha]
  (git/git-checkout git-repo :name tag-or-sha))

(defn clone! [repo]
  (let [path (Files/createTempDirectory nil (make-array FileAttribute 0))]
    (println "cloning" repo "...")
    {:path path :git (git/git-clone repo :dir (str path))}))


(defn clone-repos! [dependencies]
  (let [repo-names      (->> dependencies
                             (map :repo)
                             distinct)
        repos           (map clone! repo-names)
        repo-name->repo (zipmap repo-names repos)]
    (->> dependencies
         (map #(assoc % :repo (get repo-name->repo (:repo %)))))))

(defn write-zip-entry! [^java.util.zip.ZipInputStream zinp
                        ^java.util.zip.ZipEntry entry
                        base-path]
  (let [file-name  (append-dir base-path (.getName entry))
        size       (.getCompressedSize entry)
        ^bytes buf (byte-array 1024)]
    (if (zero? size)
      (.mkdirs (io/file file-name))
      (with-open [outp (io/output-stream file-name)]
        (println "unzipping" file-name)
        (loop []
          (let [bytes-read (.read zinp buf)]
            (when (pos? bytes-read)
              (.write outp buf 0 bytes-read)
              (recur))))))))

(defn unzip! [^java.util.zip.ZipInputStream zinp dst]
  (loop []
    (when-let [^java.util.zip.ZipEntry entry (.getNextEntry zinp)]
      (write-zip-entry! zinp entry dst)
      (.closeEntry zinp)
      (recur))))

(def os-name->os {"Linux" "linux"})
(def os-arch->arch {"amd64" "x86_64"})

(defn get-prop [prop-name]
  (if-let [v (System/getProperty prop-name)]
    v
    (throw (ex-info "unknown prop" {:prop-name prop-name}))))

(defn translate-prop [prop-map prop-name]
  (let [v  (get-prop prop-name)
        v' (get prop-map v)]
    (when-not v'
      (throw (ex-info "unknown property value" {:prop-name prop-name :prop-value v})))
    v'))

(defn get-protoc-release [protoc-version]
  (strings/join "-" ["protoc" protoc-version
                     (translate-prop os-name->os "os.name")
                     (translate-prop os-arch->arch "os.arch")]))


(defn set-protoc-permissions! [protoc-path]
  (let [permissions (java.util.HashSet.)]
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE)
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_READ)
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_WRITE)
    (java.nio.file.Files/setPosixFilePermissions (.toPath (io/file protoc-path))
                                                 permissions)))

(defn download-protoc! [release-url protoc-version protoc-release base-path]
  (let [url (str release-url "/v" protoc-version "/" protoc-release ".zip")]
    (println "Downloading protoc from" url "...")
    (let [dst (append-dir base-path protoc-release)]
      (with-open [inp (java.util.zip.ZipInputStream. (io/input-stream url))]
        (unzip! inp dst))
      dst)))


(defn run-protoc! [protoc-path opts]
  (let [{:keys [exit out err]} (apply sh/sh protoc-path opts)]
    (when-not (strings/blank? err)
      (print-err err))
    (when-not (strings/blank? out)
      (println out))
    (when-not (= 0 exit)
      (throw (ex-info "protoc failed" {:exit exit})))))

(def release-url "https://github.com/protocolbuffers/protobuf/releases/download")

(defn mkdir! [dir-path]
  (let [dir (io/file dir-path)]
    (when-not (or (.exists dir)
                  (.mkdir dir))
      (throw (ex-info "failed to create dir" {:dir dir-path})))
    dir))


(defn init-rc-dir! []
  (mkdir! (append-dir (get-prop "user.home") ".lein-prototool")))

(defn discover-files [git-repo-path dep-path]
  (filter
    (fn [^File file]
      (and (not (.isDirectory file))
           (strings/ends-with? (.getName file) ".proto")))
    (file-seq (io/file (append-dir git-repo-path dep-path)))))


(defmulti run-prototool! (fn [mode _args _project] mode))

(defmethod run-prototool! :default [mode _ _] (throw (ex-info "unknown command" {:command mode})))


(defn print-warning [& s]
  (apply print-err "WARNING:" s))

(defn strip-suffix [suffix s]
  (if (strings/ends-with? s suffix)
    (subs s 0 (- (count s) (count suffix)))
    s))

(def strip-trailing-slash (partial strip-suffix "/"))

(defn validate-output-path [output-path project]
  (when-not output-path
    (throw (ex-info "output path not defined" {})))
  (when-not (some (fn [sp]
                    (strings/ends-with? (strip-trailing-slash sp)
                                        (strip-trailing-slash output-path)))
                  (:java-source-paths project))
    (print-warning "output-path" output-path "not found in :java-source-paths")))

(defmethod run-prototool! :generate [_ _ project]
  (let [home-dir        (init-rc-dir!)
        config          (:lein-protobuf project)
        output-path     (:output-path config)
        proto-version   (:proto-version config)
        protoc-installs (append-dir home-dir "protoc-installations")
        protoc-release  (get-protoc-release proto-version)
        dependencies    (map parse-dependency (:dependencies config))]
    (when (seq dependencies)
      (validate-output-path output-path project)
      (when-not proto-version
        (throw (ex-info "proto version not defined" {})))
      (let [protoc (append-dir protoc-installs protoc-release "bin" "protoc")]
        (when-not (.exists ^File (io/file protoc))
          (download-protoc! release-url proto-version protoc-release protoc-installs)
          (set-protoc-permissions! protoc))
        (mkdir! output-path)
        (doseq [dep (clone-repos! dependencies)]
          (let [repo (:repo dep)]
            (checkout! (:git repo) (:rev dep))
            (doseq [proto-file (discover-files (:path repo) (append-dir (:root dep) (:dep-path dep)))]
              (let [protoc-opts [(str "-I" (append-dir (:path repo) (:root dep))) (str "--java_out=" output-path) (.getAbsolutePath proto-file)]]
                (println "compiling" (.getName proto-file) "...")
                (run-protoc! protoc protoc-opts)))))))))

(defn lein-protobuf
  [project & [mode & args]]
  (run-prototool! (keyword mode) args project))



