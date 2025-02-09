(ns antq.dep.clojure
  "Clojure CLI"
  (:require
   [antq.constant :as const]
   [antq.record :as r]
   [antq.util.dep :as u.dep]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.deps.alpha :as alpha]
   [clojure.tools.deps.alpha.extensions.git :as git]
   [clojure.walk :as walk]))

(def ^:private project-file "deps.edn")

(declare load-deps)

(defn user-deps-repository
  []
  (let [file (io/file (alpha/user-deps-path))]
    (when (.exists file)
      (-> file slurp edn/read-string :mvn/repos))))

(defmulti extract-type-and-version
  (fn [opt]
    (if (map? opt)
      (cond
        (contains? opt :mvn/version)
        :java

        (and (contains? opt :git/url)
             (some #(contains? opt %) [:tag :git/tag])
             (some #(contains? opt %) [:sha :git/sha]))
        :git-tag-and-sha

        (and (contains? opt :git/url)
             (some #(contains? opt %) [:sha :git/sha]))
        :git-sha

        :else
        ::unknown)
      ::unknown)))

(defmethod extract-type-and-version :default
  [_]
  {})

(defmethod extract-type-and-version :java
  [opt]
  {:type :java
   :version (:mvn/version opt)})

(defmethod extract-type-and-version :git-sha
  [opt]
  {:type :git-sha
   :version (:sha opt (:git/sha opt))
   :extra {:url (:git/url opt)}})

(defmethod extract-type-and-version :git-tag-and-sha
  [opt]
  {:type :git-tag-and-sha
   :version (:tag opt (:git/tag opt))
   :extra {:url (:git/url opt)
           :sha (:sha opt (:git/sha opt))}})

(defn- adjust-version-via-deduction
  [dep-name opt]
  (if (and (map? opt)
           (some #{:tag :sha :git/tag :git/sha} (keys opt))
           (not (:git/url opt)))
    (if-let [git-dep (git/auto-git-url dep-name)]
      (assoc opt :git/url git-dep)
      opt)
    opt))

(defn- get-relative-path-by-current-working-directory
  [current-working-directory relative-path]
  (let [file (io/file current-working-directory)
        dir (if (.isDirectory file)
              file
              (.getParentFile file))]
    (if dir
      (-> (str (u.dep/relative-path dir)
               (System/getProperty "file.separator")
               relative-path)
          (str/replace #"\./" ""))
      relative-path)))

(defn- get-local-root-relative-path
  [current-file-path opt]
  (let [local-root (:local/root opt)]
    (if (str/starts-with? local-root "/")
      local-root
      (get-relative-path-by-current-working-directory
       current-file-path local-root))))

(defn- exclude?
  [dep]
  (-> (second dep)
      (meta)
      (contains? const/deps-exclude-key)))

(defn extract-deps
  [file-path deps-edn-content-str & [loaded-dir-set]]
  (let [deps (atom [])
        edn (edn/read-string deps-edn-content-str)
        loaded-dir-set (or loaded-dir-set (atom #{}))
        cross-project-repositories (user-deps-repository)]
    (walk/postwalk (fn [form]
                     (when (and (sequential? form)
                                (const/clojure-deps-keys (first form))
                                (map? (second form)))
                       (->> form
                            (second)
                            (seq)
                            (remove exclude?)
                            (swap! deps concat)))
                     form)
                   edn)
    (->> @deps
         (mapcat (fn [[dep-name opt]]
                   (let [opt (adjust-version-via-deduction dep-name opt)
                         type-and-version (extract-type-and-version opt)]
                     (cond
                       (not (map? opt))
                       [nil]

                       (contains? opt :local/root)
                       (let [path (get-local-root-relative-path file-path opt)]
                         (load-deps path loaded-dir-set))

                       (and (string? (:version type-and-version))
                            (seq (:version type-and-version)))
                       (-> {:project :clojure
                            :file file-path
                            :name  (if (qualified-symbol? dep-name)
                                     (str dep-name)
                                     (str dep-name "/" dep-name))
                            :repositories (merge cross-project-repositories
                                                 (:mvn/repos edn))}
                           (merge type-and-version)
                           (r/map->Dependency)
                           (vector))

                       :else
                       [nil]))))
         (remove nil?))))

(defn load-deps
  ([] (load-deps "."))
  ([dir] (load-deps dir (atom #{})))
  ([dir loaded-dir-set]
   (let [dir (u.dep/normalize-path dir)]
     ;; Avoid infinite loop
     (when-not (contains? @loaded-dir-set dir)
       (swap! loaded-dir-set conj dir)
       (let [file (io/file dir project-file)]
         (when (.exists file)
           (extract-deps (u.dep/relative-path file)
                         (slurp file)
                         loaded-dir-set)))))))
