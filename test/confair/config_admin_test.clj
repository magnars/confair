(ns confair.config-admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [confair.config-admin :as sut]
            [confair.config :as config]
            [test-with-files.tools :refer [with-files]]))

(deftest conceal-reveal-test
  (with-files tmp-dir ["config.edn" (str
                                     "^" {:config/secrets {:secret/test "mypass"}}
                                     {:api-key "foobar"})]
    (let [config-path (str tmp-dir "/config.edn")]

      ;; conceal

      (is (= (sut/conceal-value (config/from-file config-path)
                                :secret/test
                                [:api-key])
             [:concealed [:api-key] :in config-path]))

      (let [config (config/from-file config-path)]
        (is (= (:api-key config) "foobar"))
        (is (= (:config/encrypted-paths (meta config)) {[:api-key] :secret/test})))

      (is (re-find #":api-key \[:secret/test " (slurp config-path)))

      (is (= (sut/conceal-value (config/from-file config-path)
                                :secret/test
                                [:api-key])
             [:path-already-encrypted [:api-key]]))

      ;; reveal

      (is (= (sut/reveal-value (config/from-file config-path)
                               [:api-key])
             [:revealed [:api-key] :in config-path]))

      (let [config (config/from-file config-path)]
        (is (= (:api-key config) "foobar"))
        (is (= (:config/encrypted-paths (meta config)) {})))

      (is (re-find #":api-key \"foobar\"" (slurp config-path)))

      (is (= (sut/reveal-value (config/from-file config-path)
                               [:api-key])
             [:path-isnt-encrypted [:api-key]])))))

(deftest dont-conceal-refs-test
  (with-files tmp-dir ["stuff.txt" "content"
                       "config.edn" (str
                                     "^" {:config/secrets {:secret/test "mypass"}}
                                     {:api-key "foobar"
                                      :stuff [:config/file (str tmp-dir "/stuff.txt")]})]
    (let [config-path (str tmp-dir "/config.edn")]
      (is (= (sut/conceal-value (config/from-file config-path)
                                :secret/test
                                [:stuff])
             [:skipping [:stuff] :defined-in [:config/file (str tmp-dir "/stuff.txt")]]))

      (is (= (sut/reveal-value (config/from-file config-path)
                               [:stuff])
             [:path-isnt-encrypted [:stuff]])))))

(deftest nested-test
  (with-files tmp-dir ["config.edn" (str
                                     "^" {:config/secrets {:secret/test "mypass"}}
                                     {:providers [{:name "Foo" :password "foo-pw"}
                                                  {:name "Bar" :password "bar-pw"}]})]
    (let [config-path (str tmp-dir "/config.edn")]
      (is (= (sut/conceal-value (config/from-file config-path)
                                :secret/test
                                [:providers 0 :password])
             [:concealed [:providers 0 :password] :in config-path]))

      (let [config (config/from-file config-path)]
        (is (= (get-in config [:providers 0 :password]) "foo-pw"))
        (is (= (:config/encrypted-paths (meta config)) {[:providers 0 :password] :secret/test})))

      (is (= (sut/conceal-value (config/from-file config-path)
                                :secret/test
                                [:providers 2])
             [:no-value-at-path [:providers 2]])))))

(deftest replace-secret-test
  (with-files tmp-dir ["foo.edn" (str {:api-key "ghosts"})
                       "bar.edn" (str {:password "goblins"})
                       "baz.edn" (str {:theme "ghouls"})]
    (is (= (sut/conceal-value (config/from-file (str tmp-dir "/foo.edn")
                                                {:secrets {:secret/test "boom"}})
                              :secret/test
                              [:api-key])
           [:concealed [:api-key] :in (str tmp-dir "/foo.edn")]))
    (is (= (sut/conceal-value (config/from-file (str tmp-dir "/bar.edn")
                                                {:secrets {:secret/test "boom"}})
                              :secret/test
                              [:password])
           [:concealed [:password] :in (str tmp-dir "/bar.edn")]))

    (is (= (set
            (sut/replace-secret {:files (sut/find-files tmp-dir #"edn$")
                                 :secret-key :secret/test
                                 :old-secret "boom"
                                 :new-secret "bang"}))
           #{[:replaced-secret [:api-key] :in (str tmp-dir "/foo.edn")]
             [:replaced-secret [:password] :in (str tmp-dir "/bar.edn")]
             [:nothing-to-do :in (str tmp-dir "/baz.edn")]}))

    (spit (str tmp-dir "/merged.edn")
          (str "^" {:config/secrets {:secret/test "bang"}
                    :dev-config/import [(str tmp-dir "/foo.edn")
                                        (str tmp-dir "/bar.edn")
                                        (str tmp-dir "/baz.edn")]}
               {:merged? true}))

    (let [config (config/from-file (str tmp-dir "/merged.edn"))]
      (is (= config {:api-key "ghosts"
                     :password "goblins"
                     :theme "ghouls"
                     :merged? true})))))
