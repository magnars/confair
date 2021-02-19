(ns confair.edn-loader
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn validate-file-existence [file-path]
  (when-not (.exists (io/file file-path))
    (throw (ex-info (str "Unable to load " file-path ", no such file.") {:file-path file-path}))))

(defn load-one-str [content source]
  (let [forms (try
                (edn/read-string (str "[" content "]"))
                (catch Exception e
                  (throw (ex-info (str "Error in " source ": " (.getMessage e))
                                  {:source source} e))))]
    (when (next forms)
      (throw (ex-info (str source " should contain only a single form, but had " (count forms) " forms.")
                      {:source source :num-forms (count forms)})))
    (first forms)))

(defn load-one
  "Read a single edn data structure from disk."
  [file-path]
  (validate-file-existence file-path)
  (load-one-str (slurp (io/file file-path)) [:file file-path]))

(defn load-one-or-nil
  "Read a single edn data structure from disk if it exists."
  [file-path]
  (let [f (io/file file-path)]
    (when (.exists f)
      (load-one-str (slurp f) [:file file-path]))))
