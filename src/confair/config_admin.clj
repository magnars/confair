(ns confair.config-admin
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [confair.config :as config]
            [rewrite-clj.zip :as z]
            [taoensso.nippy :as nippy])
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

(defn replace-map-value-in-edn-file [path k v]
  (let [root (z/of-file path)
        has-meta-data? (= :meta (:tag (first root)))]
    (-> root
        (z/down)
        (cond-> has-meta-data? (-> (z/right)
                                   (z/down)))
        (z/find-value k)
        (z/right)
        (z/replace v)
        (z/root-string)
        (->> (spit path)))))

(defn conceal-value [config secret-key key]
  (let [{:config/keys [key-sources secrets encrypted-keys]} (meta config)]
    (if-not (and key-sources secrets encrypted-keys)
      [:config-is-missing-relevant-meta-info]
      (if (encrypted-keys key)
        [:key-already-encrypted key]
        (if-let [secret (secrets secret-key)]
          (if-let [source (key-sources key)]
            (do (replace-map-value-in-edn-file source key (vector secret-key (config/encrypt (get config key) secret)))
                [:concealed key :in source])
            [:no-source-found-for key])
          [:no-secret-found-for secret-key])))))

(defn reveal-value [config key]
  (let [{:config/keys [key-sources secrets encrypted-keys]} (meta config)]
    (if-not (and key-sources secrets encrypted-keys)
      [:config-is-missing-relevant-meta-info]
      (if (encrypted-keys key)
        (if-let [source (key-sources key)]
          (do (replace-map-value-in-edn-file source key (get config key))
              [:revealed key :in source])
          [:no-source-found-for key])
        [:key-isnt-encrypted key]))))

(defn replace-secret-in-file [config secret-key old-secret new-secret]
  (let [{:config/keys [key-sources secrets encrypted-keys from-file]} (meta config)]
    (if-not (and key-sources secrets encrypted-keys from-file)
      [[:config-is-missing-relevant-meta-info]]
      (if-let [relevant-keys (seq (keep (fn [[k s-k]]
                                          (when (and (= secret-key s-k)
                                                     (= (key-sources k) from-file))
                                            k))
                                        encrypted-keys))]
        (for [key relevant-keys]
          (do (replace-map-value-in-edn-file from-file key [secret-key (config/encrypt (get config key) new-secret)])
              [:replaced-secret key :in from-file]))
        [[:nothing-to-do :in from-file]]))))

(defmacro forcat
  "`forcat` is to `for` like `mapcat` is to `map`."
  [& body]
  `(mapcat identity (for ~@body)))

(defn replace-secret [{:keys [files secret-key old-secret new-secret]}]
  (let [new-secret (config/resolve-reference ::new-secret new-secret)
        configs (for [file files]
                  (try
                    (config/from-file file {secret-key old-secret})
                    (catch Exception e [:exception-while-loading-config file (.getMessage e)])))]
    (if-let [exceptions (seq (filter vector? configs))]
      exceptions
      (forcat [config configs]
        (replace-secret-in-file config secret-key old-secret new-secret)))))
