(ns confair.config
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.string :as str]
            [confair.edn-loader :as edn-loader]
            [taoensso.nippy :as nippy])
  (:import java.util.Base64))

(defn ref? [v]
  (and (vector? v)
       (#{:config/file :config/env} (first v))))

(defn get-imports [config]
  (or (:config/import (meta config))
      (:dev-config/import (meta config)))) ;; legacy

(defn merge-config [sources]
  (when-let [s (seq (filter #(get-imports (:config %)) sources))]
    (throw (ex-info "Recursively importing config is not supported, because it is not recommended."
                    {:sources s})))
  (let [config (apply merge (map :config sources))]
    (with-meta
      config
      (assoc (meta config) :config/key-sources
             (into {} (for [k (keys config)]
                        (let [source (->> sources
                                          (filter #(contains? (:config %) k))
                                          last)
                              v (get-in source [:config k])]
                          [k (if (ref? v)
                               v ;; value is read from file/env, :file isn't true source
                               (:file source))])))))))

(defn encrypt [form password]
  (nippy/freeze-to-string form {:password [:cached password]}))

(defn decrypt [encrypted password]
  (nippy/thaw-from-string encrypted {:password [:cached password]}))

(defn- find-encrypted-paths
  ([form secrets] (find-encrypted-paths form [] secrets))
  ([form path secrets]
   (cond
     (and (vector? form) (get secrets (first form))) [[path (first form)]]
     (map? form) (mapcat (fn [[k v]]
                           (find-encrypted-paths v (conj path k) secrets))
                         form)
     (vector? form) (mapcat (fn [i v] (find-encrypted-paths v (conj path i) secrets))
                            (range)
                            form))))

(defn- decrypt-v [[secret-key encrypted] path secrets]
  (try (decrypt encrypted (get secrets secret-key))
       (catch Exception e
         (throw (ex-info (str "Unable to decrypt config key at " path)
                         {:path path :val encrypted} e)))))

(defn reveal [config secrets]
  (let [encrypted-paths (into {} (find-encrypted-paths config secrets))]
    (with-meta
      (reduce (fn [config path]
                (update-in config path decrypt-v path secrets))
              config
              (keys encrypted-paths))
      (assoc (meta config) :config/encrypted-paths encrypted-paths))))

(defn get-env [k]
  (System/getenv k))

(defn resolve-ref [k v overrides]
  (or (get overrides v)
      (if-not (ref? v)
        v
        (let [[source path] v]
          (cond
            (= :config/file source)
            (let [f (io/file path)]
              (if (.exists f)
                (if (.isFile f)
                  (str/trim (slurp f))
                  (throw (ex-info (str "Expected file, got directory " path " for " k)
                                  {k v})))
                (throw (ex-info (str "Unknown file " path " for " k)
                                {k v}))))

            (= :config/env source)
            (or (get-env path)
                (throw (ex-info (str "Unknown env variable " path " for " k)
                                {k v}))))))))

(defn resolve-refs [m overrides]
  (with-meta
    (into {}
          (for [[k v] m]
            [k (resolve-ref k v overrides)]))
    (meta m)))

(defn- mask-str [s extra]
  (str
   (cond
     (< 8 (count s)) (str (subs s 0 (+ 2 extra)) (subs "*****" extra))
     (< 3 (count s)) (str (subs s 0 (+ 1 extra)) (subs "******" extra))
     :else "*******")
   (if (< 5 (count s)) (last s) "*")))

(defn mask-secret [v]
  (cond
    (string? v)  [:config/masked-string (mask-str v 0)]
    (number? v)  [:config/masked-number (mask-str (str v) 0)]
    (boolean? v) [:config/masked-boolean "********"]
    (vector? v)  [:config/masked-vector (mask-str (str v) 1)]
    (map? v)     [:config/masked-map (mask-str (str v) 2)]
    (set? v)     [:config/masked-set (mask-str (str v) 2)]
    (list? v)    [:config/masked-list (mask-str (str v) 1)]
    :else        [:config/masked-value (mask-str (str v) 0)]))

(defn mask-secrets [config]
  (reduce
   (fn [config path]
     (cond-> config
       (not= (get-in config path ::missing) ::missing)
       (update-in path mask-secret)))
   config
   (let [m (meta config)]
     (into (set (:config/masked-paths m))
           (keys (:config/encrypted-paths m))))))

(deftype MaskedConfig [^clojure.lang.IPersistentMap config]
  Object
  (toString [_]           (str (mask-secrets config)))
  (hashCode [_]           (.hashCode [::masked-config config (:config/encrypted-paths (meta config))]))
  (equals [_ o]           (and (instance? MaskedConfig o)
                               (.equiv config (.config o))
                               (.equiv (:config/encrypted-paths (meta config))
                                       (:config/encrypted-paths (meta (.config o))))))

  clojure.lang.IMeta
  (meta [_]               (.meta config))

  clojure.lang.IPersistentMap
  (count [_]              (.count config))
  (empty [_]              (MaskedConfig. (.empty config)))
  (cons  [_ x]            (MaskedConfig. (.cons config x)))
  (equiv [this o]         (.equals this o))
  (containsKey [_ k]      (.containsKey config k))
  (entryAt [_ k]          (.entryAt config k))
  (seq [_]                (.seq config))
  (iterator [_]           (.iterator config))
  (assoc [_ k v]          (MaskedConfig. (assoc config k v)))
  (without [_ k]          (MaskedConfig. (dissoc config k)))

  clojure.lang.ILookup
  (valAt [_ k]            (config k))
  (valAt [_ k not-found]  (config k not-found))

  clojure.lang.IFn
  (invoke [_ k]           (config k))
  (invoke [_ k not-found] (config k not-found)))

(defmethod print-method MaskedConfig [m w]
  (.write w (str m)))

(defmethod clojure.pprint/simple-dispatch MaskedConfig [m]
  (.write *out* (with-out-str (pprint (mask-secrets (.config m))))))

(defn mask-config [config]
  (MaskedConfig. config))

(defn from-file [path & [overrides]]
  (let [raw-config (edn-loader/load-one path)
        config (merge-config (concat (for [f (get-imports raw-config)]
                                       {:file f :config (edn-loader/load-one f)})
                                     [{:file path :config (vary-meta raw-config dissoc :config/import :dev-config/import)}]))
        secrets (resolve-refs (or (:secrets overrides)
                                  (:config/secrets (meta raw-config)))
                              (:refs overrides))]
    (vary-meta (-> config
                   (resolve-refs (:refs overrides))
                   (reveal secrets))
               assoc
               :config/secrets secrets
               :config/from-file path)))

(defn from-string [s source & [overrides]]
  (let [config (edn-loader/load-one-str s source)
        secrets (resolve-refs (or (:secrets overrides)
                                  (:config/secrets (meta config)))
                              (:refs overrides))]
    (vary-meta (-> config
                   (resolve-refs (:refs overrides))
                   (reveal secrets))
               dissoc :config/secrets)))

(defn from-base64-string [s source & [overrides]]
  (from-string (String. (.decode (Base64/getDecoder) s)) [:base64-string source] overrides))

(defn from-env [env-var & [overrides]]
  (from-string (get-env env-var) [:env env-var] overrides))

(defn from-base64-env [env-var & [overrides]]
  (from-base64-string (get-env env-var) [:env env-var] overrides))

;; verify

(defn verify-required-together [config key-bundles]
  (doseq [bundle key-bundles]
    (let [present (set/intersection (set (keys config)) (set bundle))
          missing (set/difference (set bundle) present)]
      (when (and (seq present) (seq missing))
        (throw (ex-info (str "Config keys " bundle " are required together, found only " present ".")
                        {:missing missing :present present})))))
  config)

(defn verify-dependent-keys [config kv-spec->required-keys]
  (doseq [[kv-spec required-keys] kv-spec->required-keys]
    (when (every? (fn [[k f]] (f (get config k))) kv-spec)
      (let [present (set/intersection (set (keys config)) (set required-keys))
            missing (set/difference (set required-keys) present)]
        (when (seq missing)
          (let [match (select-keys config (keys kv-spec))]
            (throw (ex-info (str "Missing config keys " missing " are required due to " match ".")
                            {:reason match :missing missing :present present})))))))
  config)
