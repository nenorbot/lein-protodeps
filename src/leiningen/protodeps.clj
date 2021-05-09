(ns leiningen.protodeps
  (:require [clojure.java.io :as io]
            [clojure.string :as strings]
            [clojure.java.shell :as sh]
            [clojure.set :as sets])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.nio.file Path]))

(def ^:dynamic *verbose?* false)

(defn- verbose-prn [msg & args]
  (when *verbose?*
    (println "protodeps:" (apply format msg args))))


(defn print-err [& s]
  (binding [*out* *err*]
    (apply println s)))

(defn print-warning [& s]
  (apply print-err "protodeps: WARNING:" s))

(defn append-dir [parent & children]
  (strings/join File/separator (concat [parent] children)))


(defn create-temp-dir!
  ([] (create-temp-dir! nil))
  ([^Path base-path]
   (let [file-attrs (make-array FileAttribute 0)]
     (if base-path
       (Files/createTempDirectory base-path nil file-attrs)
       (Files/createTempDirectory nil file-attrs)))))


(defn- interpolate [m s]
  (reduce
    (fn [s [k v]]
      (strings/replace s (format "${%s}" (str k)) (str v)))
    s
    m))


(defn run-sh!
  ([cmd opts] (run-sh! cmd opts nil))
  ([cmd opts m]
   (verbose-prn (str "running " cmd " with opts: %s") opts)
   (let [{:keys [exit] :as r} (apply sh/sh cmd (map (partial interpolate m) opts))]
     (if (= 0 exit)
       r
       (throw (ex-info (str cmd " failed") r))))))


(defn- sha? [s]
  (re-matches #"^[A-Za-z0-9]{40}$" s))


(defn- git-clone! [repo-url dir rev]
  (let [dir  (str dir)
        conf (into {}
                   (map
                     (fn [[k v]]
                       [(keyword "env" k) v]))
                   (System/getenv))]
    (if (sha? rev)
      (do
        (run-sh! "git" ["clone" repo-url dir] conf)
        (run-sh! "git" ["-C" dir "checkout" rev]))
      (run-sh! "git" ["clone" repo-url "--branch" rev "--single-branch" "--depth" "1" dir] conf))))


(defn clone! [repo-name base-path repo-config]
  (let [git-config (:config repo-config)
        path       (str (create-temp-dir! base-path))
        repo-url   (:clone-url git-config)
        rev        (:rev git-config)]
    (println "cloning" repo-name "at rev" rev "...")
    (when-not rev
      (throw  (ex-info (str ":rev is not set for " repo-name ", set a git tag/branch name/commit hash") {})))
    (git-clone! repo-url path rev)
    path))


(defmulti resolve-repo (fn [_ctx repo-config] (:repo-type repo-config)))

(defmethod resolve-repo :git [ctx repo-config]
  (clone! (:repo-name ctx) (:base-path ctx) repo-config))

(defmethod resolve-repo :filesystem [_ repo-config]
  (some-> repo-config :config :path io/file .getAbsolutePath))

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

(def os-name->os {"Linux" "linux" "Mac OS X" "osx"})
(def os-arch->arch {"amd64" "x86_64" "x86_64" "x86_64"})

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

(defn- get-protoc-release [protoc-version]
  (strings/join "-" ["protoc" protoc-version
                     (translate-prop os-name->os "os.name")
                     (translate-prop os-arch->arch "os.arch")]))

(def ^:private grpc-plugin-executable-name "protoc-gen-grpc-java")

(defn- get-grpc-release [grpc-version]
  (str
    (strings/join "-" [grpc-plugin-executable-name
                       grpc-version
                       (translate-prop os-name->os "os.name")
                       (translate-prop os-arch->arch "os.arch")])
    ".exe"))

(defn set-protoc-permissions! [protoc-path]
  (let [permissions (java.util.HashSet.)]
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE)
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_READ)
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_WRITE)
    (java.nio.file.Files/setPosixFilePermissions (.toPath (io/file protoc-path))
                                                 permissions)))

(defn download-protoc! [release-url protoc-version protoc-release base-path]
  (let [url (str release-url "/v" protoc-version "/" protoc-release ".zip")]
    (println "protodeps: Downloading protoc from" url "...")
    (let [dst (append-dir base-path protoc-release)]
      (with-open [inp (java.util.zip.ZipInputStream. (io/input-stream url))]
        (unzip! inp dst))
      dst)))

(defn- download-grpc-plugin! [grpc-release-url grpc-version grpc-release grpc-plugin-file]
  (let [url (str grpc-release-url "/" grpc-version "/" grpc-release)]
    (println "protodeps: Downloading grpc java plugin from" url "...")
    (with-open [input-stream  (io/input-stream url)
                output-stream (io/output-stream (io/file grpc-plugin-file))]
      (io/copy input-stream output-stream))))


(defn run-protoc-and-report! [protoc-path opts]
  (let [{:keys [out err]} (run-sh! protoc-path opts)]
    (when-not (strings/blank? err)
      (print-err err))
    (when-not (strings/blank? out)
      (println out))))

(def protoc-release-url "https://github.com/protocolbuffers/protobuf/releases/download")

(def grpc-release-url "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java")

(defn mkdir! [dir-path]
  (let [dir (io/file dir-path)]
    (when-not (or (.exists dir)
                  (.mkdirs dir))
      (throw (ex-info "failed to create dir" {:dir dir-path})))
    dir))


(def ^:private protoc-install-dir "protoc-installations")
(def ^:private grpc-install-dir "grpc-installations")

(defn init-rc-dir! []
  (let [home (append-dir (get-prop "user.home") ".lein-prototool")]
    (mkdir! home)
    (mkdir! (append-dir home protoc-install-dir))
    (mkdir! (append-dir home grpc-install-dir))
    home))

(defn discover-files [git-repo-path dep-path]
  (filter
    (fn [^File file]
      (and (not (.isDirectory file))
           (strings/ends-with? (.getName file) ".proto")))
    (file-seq (io/file (append-dir git-repo-path dep-path)))))


(defmulti run-prototool! (fn [mode _args _project] mode))

(defmethod run-prototool! :default [mode _ _] (throw (ex-info "unknown command" {:command mode})))

(defn long-opt [k v]
  (str "--" k "=" v))

(defn with-proto-paths [protoc-args proto-paths]
  (into protoc-args
        (map (partial long-opt "proto_path")
             proto-paths)))

(defn get-file-dependencies [protoc-path proto-paths ^File proto-file]
  (map io/file
       (re-seq #"[^\s]*\.proto" (:out (run-sh! protoc-path
                                               (with-proto-paths
                                                 [(long-opt "dependency_out" "/dev/stdout")
                                                  "-o/dev/null"
                                                  (.getAbsolutePath proto-file)]
                                                 proto-paths))))))

(defn expand-dependencies [protoc-path proto-paths proto-files]
  (loop [seen-files (set proto-files)
         [f & r]    proto-files]
    (if-not f
      seen-files
      (let [deps (get-file-dependencies protoc-path proto-paths f)]
        (recur (conj seen-files f)
               (concat
                 r
                 (filter
                   (fn [^File afile]
                     (not (some #(Files/isSameFile (.toPath ^File %) (.toPath afile)) seen-files)))
                   deps)))))))

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

(defn cleanup-dir! [^Path path]
  (doseq [file (reverse (file-seq (.toFile path)))]
    (.delete ^File file)))


(defn protoc-opts [proto-paths output-path compile-grpc? grpc-plugin ^File proto-file]
  (let [protoc-opts (with-proto-paths [(long-opt "java_out" output-path)] proto-paths)]
    (cond-> protoc-opts
      compile-grpc? (conj (long-opt "grpc-java_out" output-path))
      compile-grpc? (conj (long-opt "plugin" grpc-plugin))
      true          (conj (.getAbsolutePath proto-file)))))

(defn- parse-args [args]
  (let [verbosely {:verbose true}
        valid-args {:verbose verbosely :-v verbosely :--verbose verbosely
                    :--keep-tmp-dir {:keep-tmp? true}}]
    (loop [sargs    (vec (set args))
           args-res {}]
      (if (empty? sargs)
        args-res
        (let [arg (first sargs)]
          (recur (rest sargs) (merge args-res (get valid-args (keyword arg)))))))))


(defn- get-protoc! [home-dir config]
  (let [proto-version (:proto-version config)]
    (when-not proto-version
      (throw (ex-info "proto version not defined" {})))
    (let [protoc-installs (append-dir home-dir protoc-install-dir)
          protoc-release  (get-protoc-release proto-version)
          protoc (append-dir protoc-installs protoc-release "bin" "protoc")]
      (when-not (.exists ^File (io/file protoc))
        (download-protoc! protoc-release-url proto-version protoc-release protoc-installs)
        (set-protoc-permissions! protoc))
      protoc)))


(defn- get-grpc-plugin! [home-dir config]
  (let [grpc-version    (:grpc-version config)
        grpc-installs   (append-dir home-dir grpc-install-dir)
        grpc-plugin-dir (append-dir grpc-installs grpc-version)
        grpc-plugin     (append-dir grpc-plugin-dir grpc-plugin-executable-name)
        grpc-release    (get-grpc-release grpc-version)]
    (when (:compile-grpc? config)
      (when (not (.exists ^File (io/file grpc-plugin)))
        (when-not (.mkdirs (io/file grpc-plugin-dir))
          (throw (ex-info "cannot create gRPC plugin dir" {:dir grpc-plugin-dir})))
        (download-grpc-plugin! grpc-release-url grpc-version grpc-release grpc-plugin)
        (set-protoc-permissions! grpc-plugin))
      grpc-plugin)))


(defn generate-files! [args config]
  (let [home-dir           (init-rc-dir!)
        repos-config       (:repos config)
        output-path        (:output-path config)
        base-temp-path     (create-temp-dir!)
        ctx                {:base-path base-temp-path}
        keep-tmp?          (true? (:keep-tmp? args))
        protoc             (get-protoc! home-dir config)
        grpc-plugin        (get-grpc-plugin! home-dir config)
        repo-id->repo-path (into {}
                                 (map
                                  (fn [[k v]]
                                    (let [ctx (assoc ctx :repo-name k)]
                                      [k (resolve-repo ctx v)])))
                                 repos-config)
        proto-paths        (mapcat (fn [[repo-id repo-conf]]
                                     (map #(append-dir (get repo-id->repo-path repo-id) %)
                                          (:proto-paths repo-conf)))
                                   repos-config)]
    (try
      (mkdir! output-path)
      (verbose-prn "config: %s" config)
      (verbose-prn "paths: %s" {:protoc      protoc
                                :grpc-plugin grpc-plugin})
      (verbose-prn "output-path: %s" output-path)
      (doseq [[repo-id repo] repos-config]
        (let [repo-path   (get repo-id->repo-path repo-id)
              proto-files (transduce (map
                                      (fn [[proto-dir]]
                                        (expand-dependencies protoc proto-paths
                                                             (discover-files repo-path (str proto-dir)))))
                                     sets/union
                                     (:dependencies repo))]
          (verbose-prn "files: %s" (mapv #(.getName ^File %) proto-files))
          (when (empty? proto-files)
            (print-warning "could not find any .proto files"))
          (doseq [proto-file proto-files]
            (let [protoc-opts (protoc-opts proto-paths
                                           output-path
                                           (:compile-grpc? config)
                                           grpc-plugin
                                           proto-file)]
              (println "compiling" (.getName proto-file) "...")
              (run-protoc-and-report! protoc protoc-opts)))))

      (finally
        (if keep-tmp?
          (println "generated" base-temp-path)
          (cleanup-dir! base-temp-path))))))

(defmethod run-prototool! :generate [_mode args project]
  (let [parsed-args     (parse-args args)
        config          (:lein-protodeps project)
        output-path     (:output-path config)]
    (binding [*verbose?* (:verbose parsed-args)]
      (verbose-prn "config: %s" config)
      (validate-output-path output-path project)
      (generate-files! parsed-args config))))

(defn protodeps
  [project & [mode & args]]
  (run-prototool! (keyword mode) args project))

(comment
  (def config '{:output-path   "src/java/generated"
                :proto-version "3.12.4"
                :grpc-version  "1.30.2"
                :compile-grpc? true
                :repos         {:af-proto #_ {:repo-type    :filesystem
                                              :config       {:path "/home/ronen/Projects/af-proto"}
                                              :dependencies [{:proto-path "products" :proto-dir "events"}]}
                                {:repo-type    :git
                                 :proto-paths  ["products"]
                                 :config       {:clone-url   "git@localhost:test/repo.git"
                                                :rev         "origin/mybranch"}
                                 :dependencies [[products/events]]}}}))
