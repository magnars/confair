(ns confair.config-admin
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [confair.config :as config]
            [rewrite-clj.zip :as z]
            [rewrite-clj.zip.base :as bz])
  (:import java.io.File
           java.util.regex.Pattern))

(def fsep-regex (Pattern/compile (File/separator)))

(defn emacs-file? [^File file]
  (let [filename (last (str/split (.getPath file) fsep-regex))]
    (or (str/starts-with? filename ".#")
        (and (str/starts-with? filename "#")
             (str/ends-with? filename "#")))))

(defn find-files [dir regexp]
  (->> (file-seq (io/as-file dir))
       (remove emacs-file?)
       (map (fn [^File file] (.getPath file)))
       (filter #(re-find regexp %))))

(defn z-meta? [zloc]
  (= (bz/tag zloc) :meta))

(defn z-to-k [zloc k]
  (cond
    (z-meta? zloc)
    (z-to-k (-> zloc z/down z/right) k)

    (z/map? zloc)
    (-> zloc
        (z/down)
        (z/find-value k)
        (z/right))

    (z/vector? zloc)
    (if (number? k)
      (first (drop k (iterate z/right (z/down zloc))))
      (throw (ex-info (str "Can't traverse into vector with " k) {:zloc zloc :k k})))

    :else (throw (ex-info "Can only traverse into maps and vectors" {:zloc zloc :k k}))))

(defn assoc-in-edn-file [file-path path v]
  (-> (reduce z-to-k (z/of-file file-path) path)
      (z/replace v)
      (z/root-string)
      (->> (spit file-path))))

(defn conceal-value [config secret-key path]
  (let [path (if (keyword? path) [path] path)
        {:config/keys [key-sources secrets encrypted-paths]} (meta config)]
    (if-not (and key-sources secrets encrypted-paths)
      [:config-is-missing-relevant-meta-info]
      (if (encrypted-paths path)
        [:path-already-encrypted path]
        (if-let [secret (secrets secret-key)]
          (if-let [source (key-sources (first path))]
            (if (config/ref? source)
              [:skipping path :defined-in source]
              (if (get-in config path)
                (do (assoc-in-edn-file source path (vector secret-key (config/encrypt (get-in config path) secret)))
                    [:concealed path :in source])
                [:no-value-at-path path]))
            [:no-source-found-for path])
          [:no-secret-found-for secret-key])))))

(defn reveal-value [config path]
  (let [path (if (keyword? path) [path] path)
        {:config/keys [key-sources secrets encrypted-paths]} (meta config)]
    (if-not (and key-sources secrets encrypted-paths)
      [:config-is-missing-relevant-meta-info]
      (if (encrypted-paths path)
        (if-let [source (key-sources (first path))]
          (do (assoc-in-edn-file source path (get-in config path))
              [:revealed path :in source])
          [:no-source-found-for path])
        [:path-isnt-encrypted path]))))

(defn- replace-secret-in-file [config secret-key new-secret]
  (let [{:config/keys [key-sources secrets encrypted-paths from-file]} (meta config)]
    (if-not (and key-sources secrets encrypted-paths from-file)
      [[:config-is-missing-relevant-meta-info]]
      (if-let [relevant-paths (seq (keep (fn [[path s-k]]
                                           (when (and (= secret-key s-k)
                                                      (= (key-sources (first path)) from-file))
                                             path))
                                         encrypted-paths))]
        (for [path relevant-paths]
          (do (assoc-in-edn-file from-file path [secret-key (config/encrypt (get-in config path) new-secret)])
              [:replaced-secret path :in from-file]))
        [[:nothing-to-do :in from-file]]))))

(defn replace-secret [{:keys [files secret-key old-secret new-secret]} & [overrides]]
  (let [new-secret (config/resolve-ref ::new-secret new-secret nil)
        configs (for [file files]
                  (try
                    (config/from-file file (assoc-in overrides [:secrets secret-key] old-secret))
                    (catch Exception e [:exception-while-loading-config file (.getMessage e)])))]
    (if-let [exceptions (seq (filter vector? configs))]
      exceptions
      (doall (mapcat #(replace-secret-in-file % secret-key new-secret) configs)))))
