(ns confair.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [confair.config :as sut]
            [test-with-files.tools :refer [with-files]]))

(deftest merging
  (is (= (sut/merge-config
          [{:file "./foo.edn" :config {:foo 1 :bar 2 :cool? true}}
           {:file "./bar.edn" :config {:bar 3 :baz 4 :cool? false}}])
         {:foo 1 :bar 3 :baz 4 :cool? false}))

  (is (= (-> (sut/merge-config
              [{:file "./foo.edn" :config {:foo 1 :bar 2 :cool? true}}
               {:file "./bar.edn" :config {:bar 3 :baz 4 :cool? false}}])
             meta :config/key-sources)
         {:foo "./foo.edn"
          :bar "./bar.edn"
          :baz "./bar.edn"
          :cool? "./bar.edn"})))

(deftest revealing
  (is (= (sut/reveal {:plain-text "foo"
                      :encrypted [:secret/test (sut/encrypt [1 2 3] "my-secret")]}
                     {:secret/test "my-secret"})
         {:plain-text "foo"
          :encrypted [1 2 3]}))

  (is (= (-> (sut/reveal {:plain-text "foo"
                          :encrypted [:secret/test (sut/encrypt [1 2 3] "my-secret")]}
                         {:secret/test "my-secret"})
             meta :config/encrypted-keys)
         {:encrypted :secret/test})))

(deftest resolve-secrets
  (is (= (sut/resolve-secrets
          {:secret/test "yaaah"})
         {:secret/test "yaaah"}))

  (with-files tmp-dir ["foo.txt" "booyah"]
    (is (= (sut/resolve-secrets
            {:secret/test [:config/file (str tmp-dir "/foo.txt")]})
           {:secret/test "booyah"})))

  (with-redefs [sut/get-env #(when (= "MYENV" %) "fools and kings")]
    (is (= (sut/resolve-secrets
            {:secret/test [:config/env "MYENV"]})
           {:secret/test "fools and kings"}))))

(deftest from-file-integration-test
  (with-files tmp-dir ["password.txt" "cancan\n"
                       "main.edn" (str
                                   "^" {:config/secrets {:secret/test [:config/file (str tmp-dir "/password.txt")]}
                                        :dev-config/import [(str tmp-dir "/base.edn")
                                                            (str tmp-dir "/overrides.edn")]}
                                   {:main? true})
                       "base.edn" (str
                                   {:plain-text "base"
                                    :encrypted [:secret/test (sut/encrypt "this is sparta" "cancan")]})
                       "overrides.edn" (str
                                        {:plain-text "override"})]
    (is (= (sut/from-file (str tmp-dir "/main.edn"))
           {:plain-text "override"
            :encrypted "this is sparta"
            :main? true}))

    (is (= (meta (sut/from-file (str tmp-dir "/main.edn")))
           {:config/key-sources {:plain-text (str tmp-dir "/overrides.edn")
                                 :encrypted (str tmp-dir "/base.edn")
                                 :main? (str tmp-dir "/main.edn")}
            :config/encrypted-keys {:encrypted :secret/test}
            :config/secrets {:secret/test "cancan"}
            :config/from-file (str tmp-dir "/main.edn")}))))

(deftest from-env-test
  (with-redefs [sut/get-env #(case %
                               "MYPASS" "cancan"
                               "MYCONF" (str
                                         "^" {:config/secrets {:secret/prod [:config/env "MYPASS"]}}
                                         {:plain-text "base"
                                          :encrypted [:secret/prod (sut/encrypt "this is sparta" "cancan")]}))]
    (is (= (sut/from-env "MYCONF")
           {:plain-text "base"
            :encrypted "this is sparta"}))))

(deftest mask-secrets-test
  (is (= (sut/mask-secrets ^{:config/encrypted-keys {:encrypted-str :secret/test
                                                     :encrypted-vec :secret/test
                                                     :encrypted-num :secret/test}}
                           {:encrypted-str "this is sparta"
                            :encrypted-vec [1 2 3 4]
                            :encrypted-num 1
                            :plain-text "override"})
         {:encrypted-str [:config/masked-string "th*****a"]
          :encrypted-vec [:config/masked-vector "[1 ****]"]
          :encrypted-num [:config/masked-number "********"]
          :plain-text "override"}))

  (is (= (sut/mask-secret false)      [:config/masked-boolean "********"]))
  (is (= (sut/mask-secret {:foo 123})     [:config/masked-map "{:fo***}"]))
  (is (= (sut/mask-secret #{1 2 3 4})     [:config/masked-set "#{1 ***}"]))
  (is (= (sut/mask-secret '(1 2 3 4))    [:config/masked-list "(1 ****)"]))
  (is (= (sut/mask-secret #inst "2021") [:config/masked-value "Fr*****1"])))

(deftest mask-config-test
  (let [masked (sut/mask-config ^{:config/encrypted-keys {:encrypted :secret/test}}
                                {:encrypted "this is sparta"
                                 :plain-text "hi"})]
    (is (= (:encrypted masked) "this is sparta"))
    (is (= (:plain-text masked) "hi"))
    (is (= (str masked) "{:encrypted [:config/masked-string \"th*****a\"], :plain-text \"hi\"}"))

    (testing "hashcode should mirror equiv"
      (let [same (sut/mask-config ^{:config/encrypted-keys {:encrypted :secret/test}}
                                  {:encrypted "this is sparta"
                                   :plain-text "hi"})]

        (is (= masked same))
        (is (= (hash masked) (hash same))))

      (let [unmasked {:encrypted "this is sparta"
                      :plain-text "hi"}]
        (is (not= masked unmasked))
        (is (not= (hash masked) (hash unmasked))))

      (let [different-secrets (sut/mask-config ^{:config/encrypted-keys {:plain-text :secret/test}}
                                               {:encrypted "this is sparta"
                                                :plain-text "hi"})]
        (is (not= masked different-secrets))
        (is (not= (hash masked) (hash different-secrets))))

      (let [same-w-different-unrelated-meta (sut/mask-config ^{:config/encrypted-keys {:encrypted :secret/test}
                                                               :unrelated-meta? true}
                                                             {:encrypted "this is sparta"
                                                              :plain-text "hi"})]

        (is (= masked same-w-different-unrelated-meta))
        (is (= (hash masked) (hash same-w-different-unrelated-meta)))))

    (testing "you can't unmask secrets by changing map"
      (is (= (str (assoc masked :more-infos "hello"))  "{:encrypted [:config/masked-string \"th*****a\"], :plain-text \"hi\", :more-infos \"hello\"}"))
      (is (= (str (conj masked [:more-infos "hello"])) "{:encrypted [:config/masked-string \"th*****a\"], :plain-text \"hi\", :more-infos \"hello\"}"))
      (is (= (str (into (empty masked) masked))        "{:encrypted [:config/masked-string \"th*****a\"], :plain-text \"hi\"}"))
      (is (= (str (dissoc masked :plain-text))         "{:encrypted [:config/masked-string \"th*****a\"]}")))

    (is (= (with-out-str (clojure.pprint/pprint (assoc masked :something-else "pretty long"))) ;; needs to be longer to trigger multiline map
           "{:encrypted [:config/masked-string \"th*****a\"],\n :plain-text \"hi\",\n :something-else \"pretty long\"}\n\n"))))

(def required-together ;; all or nothing
  #{[:spotify/api-token-url
     :spotify/api-url
     :spotify/client-id
     :spotify/client-secret]
    [:datadog/host
     :datadog/port]})

(deftest verify-required-together-test
  (is (= (sut/verify-required-together {:foo "bar"} required-together)
         {:foo "bar"}))

  (let [config {:spotify/api-token-url "https://accounts.spotify.com/api/token"
                :spotify/api-url "https://api.spotify.com"
                :spotify/client-id "my-api-client"
                :spotify/client-secret "3abdc"}]
    (is (= (sut/verify-required-together config required-together)
           config)))

  (let [e (try (sut/verify-required-together {:spotify/api-url "..."} required-together)
               (catch Exception e e))]
    (is (instance? java.lang.Exception e))
    (is (= (.getMessage e) "Config keys [:spotify/api-token-url :spotify/api-url :spotify/client-id :spotify/client-secret] are required together, found only #{:spotify/api-url}."))
    (is (= (ex-data e) {:present #{:spotify/api-url}
                        :missing #{:spotify/api-token-url :spotify/client-id :spotify/client-secret}}))))

(def dependent-required-keys
  {{:order-backend #{:vite-crm}} #{:vite-crm/url
                                   :vite-crm/subscription-key}
   {:order-backend #{:cactus}} #{:cactus/agent-id
                                 :cactus/order-token
                                 :cactus/order-url}
   {:order-backend #{:mdb-sale}} #{:mdb-sale/api-key
                                   :mdb-sale/order-url}
   {:sms-provider #{:twilio}} #{:twilio/account-id
                                :twilio/secret}})

