(ns confair.config-test
  (:require [clojure.pprint]
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
             meta :config/encrypted-paths)
         {[:encrypted] :secret/test}))

  (testing "nested secrets"
    (is (= (sut/reveal {:plain-text "foo"
                        :nested {:encrypted [:secret/test (sut/encrypt [1 2 3] "my-secret")]}}
                       {:secret/test "my-secret"})
           {:plain-text "foo"
            :nested {:encrypted [1 2 3]}}))

    (is (= (-> (sut/reveal {:plain-text "foo"
                            :nested {:encrypted [:secret/test (sut/encrypt [1 2 3] "my-secret")]}}
                           {:secret/test "my-secret"})
               meta :config/encrypted-paths)
           {[:nested :encrypted] :secret/test}))))

(deftest resolve-refs
  (is (= (sut/resolve-refs
          {:secret/test "yaaah"}
          nil)
         {:secret/test "yaaah"}))

  (with-files tmp-dir ["foo.txt" "booyah"]
    (is (= (sut/resolve-refs
            {:secret/test [:config/file (str tmp-dir "/foo.txt")]}
            nil)
           {:secret/test "booyah"})))

  (with-redefs [sut/get-env #(when (= "MYENV" %) "fools and kings")]
    (is (= (sut/resolve-refs
            {:secret/test [:config/env "MYENV"]}
            nil)
           {:secret/test "fools and kings"})))

  (is (= (sut/resolve-refs
          {:secret/test [:config/env "MYENV"]}
          {[:config/env "MYENV"] "fake fools and kings"})
         {:secret/test "fake fools and kings"})))

(deftest from-file-integration-test
  (with-files tmp-dir ["password.txt" "cancan\n"
                       "main.edn" (str
                                   "^" {:config/secrets {:secret/test [:config/file (str tmp-dir "/password.txt")]}
                                        :config/import [(str tmp-dir "/base.edn")
                                                        (str tmp-dir "/overrides.edn")]}
                                   {:main? true
                                    :my-ip [:config/env "MY_IP"]})
                       "base.edn" (str
                                   {:plain-text "base"
                                    :encrypted [:secret/test (sut/encrypt "this is sparta" "cancan")]
                                    :providers [{:name "Foo" :password [:secret/test (sut/encrypt "foo-pw" "cancan")]}
                                                {:name "Bar" :password [:secret/test (sut/encrypt "bar-pw" "cancan")]}]})
                       "something.txt" "booyah"
                       "overrides.edn" (str
                                        {:plain-text "override"
                                         :straight-from-disk [:config/file (str tmp-dir "/something.txt")]})]
    (is (= (sut/from-file (str tmp-dir "/main.edn")
                          {:refs {[:config/env "MY_IP"] "1.2.3.5"}})
           {:plain-text "override"
            :straight-from-disk "booyah"
            :encrypted "this is sparta"
            :providers [{:name "Foo" :password "foo-pw"}
                        {:name "Bar" :password "bar-pw"}]
            :main? true
            :my-ip "1.2.3.5"}))

    (is (= (meta (sut/from-file (str tmp-dir "/main.edn")
                                {:refs {[:config/env "MY_IP"] "1.2.3.5"}}))
           {:config/key-sources {:plain-text (str tmp-dir "/overrides.edn")
                                 :straight-from-disk [:config/file (str tmp-dir "/something.txt")]
                                 :my-ip [:config/env "MY_IP"]
                                 :encrypted (str tmp-dir "/base.edn")
                                 :providers (str tmp-dir "/base.edn")
                                 :main? (str tmp-dir "/main.edn")}
            :config/encrypted-paths {[:encrypted] :secret/test
                                     [:providers 0 :password] :secret/test
                                     [:providers 1 :password] :secret/test}
            :config/secrets {:secret/test "cancan"}
            :config/from-file (str tmp-dir "/main.edn")}))))

(deftest from-env-test
  (with-redefs [sut/get-env #(case %
                               "MYPASS" "cancan"
                               "MYIP" "127.0.0.1"
                               "MYCONF" (str
                                         "^" {:config/secrets {:secret/prod [:config/env "MYPASS"]}}
                                         {:plain-text "base"
                                          :my-ip [:config/env "MYIP"]
                                          :encrypted [:secret/prod (sut/encrypt "this is sparta" "cancan")]}))]
    (is (= (sut/from-env "MYCONF")
           {:plain-text "base"
            :my-ip "127.0.0.1"
            :encrypted "this is sparta"}))))

(deftest mask-secrets-test
  (is (= (sut/mask-secrets ^{:config/encrypted-paths {[:encrypted-str] :secret/test
                                                      [:encrypted-vec] :secret/test
                                                      [:encrypted-num] :secret/test}}
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
  (let [masked (sut/mask-config ^{:config/encrypted-paths {[:encrypted] :secret/test}}
                                {:encrypted "this is sparta"
                                 :plain-text "hi"})]
    (is (= (:encrypted masked) "this is sparta"))
    (is (= (:plain-text masked) "hi"))
    (is (= (str masked) "{:encrypted [:config/masked-string \"th*****a\"], :plain-text \"hi\"}"))

    (testing "hashcode should mirror equiv"
      (let [same (sut/mask-config ^{:config/encrypted-paths {[:encrypted] :secret/test}}
                                  {:encrypted "this is sparta"
                                   :plain-text "hi"})]

        (is (= masked same))
        (is (= (hash masked) (hash same))))

      (let [unmasked {:encrypted "this is sparta"
                      :plain-text "hi"}]
        (is (not= masked unmasked))
        (is (not= (hash masked) (hash unmasked))))

      (let [different-secrets (sut/mask-config ^{:config/encrypted-paths {[:plain-text] :secret/test}}
                                               {:encrypted "this is sparta"
                                                :plain-text "hi"})]
        (is (not= masked different-secrets))
        (is (not= (hash masked) (hash different-secrets))))

      (let [same-w-different-unrelated-meta (sut/mask-config ^{:config/encrypted-paths {[:encrypted] :secret/test}
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
    (is (= (type e) clojure.lang.ExceptionInfo))
    (is (= (.getMessage e) "Config keys [:spotify/api-token-url :spotify/api-url :spotify/client-id :spotify/client-secret] are required together, found only #{:spotify/api-url}."))
    (is (= (ex-data e) {:present #{:spotify/api-url}
                        :missing #{:spotify/api-token-url :spotify/client-id :spotify/client-secret}}))))

(def dependent-keys
  {{:sms-provider #{:twilio}} #{:twilio/account-id
                                :twilio/secret}
   {:sms-provider #{:link-mobility}
    :send-sms? true?} #{:link-mobility/url
                        :link-mobility/username
                        :link-mobility/password}})

(deftest verify-dependent-keys
  (is (= (sut/verify-dependent-keys {:foo "bar"} dependent-keys)
         {:foo "bar"}))

  (let [config {:sms-provider :twilio
                :twilio/account-id "123"
                :twilio/secret "456"}]
    (is (= (sut/verify-dependent-keys config dependent-keys)
           config)))

  (let [e (try (sut/verify-dependent-keys {:sms-provider :twilio} dependent-keys)
               (catch Exception e e))]
    (is (= (type e) clojure.lang.ExceptionInfo))
    (is (#{"Missing config keys #{:twilio/account-id :twilio/secret} are required due to {:sms-provider :twilio}."
           "Missing config keys #{:twilio/secret :twilio/account-id} are required due to {:sms-provider :twilio}."}
         (.getMessage e)))
    (is (= (ex-data e) {:reason {:sms-provider :twilio}
                        :present #{}
                        :missing #{:twilio/account-id :twilio/secret}})))

  (let [config {:sms-provider :link-mobility
                :send-sms? false}]
    (is (= (sut/verify-dependent-keys config dependent-keys)
           config)))

  (let [e (try (sut/verify-dependent-keys {:sms-provider :link-mobility
                                           :send-sms? true
                                           :link-mobility/username "foo"
                                           :link-mobility/password "bar"}
                                          dependent-keys)
               (catch Exception e e))]
    (is (= (type e) clojure.lang.ExceptionInfo))
    (is (#{"Missing config keys #{:link-mobility/url} are required due to {:sms-provider :link-mobility, :send-sms? true}."
           "Missing config keys #{:link-mobility/url} are required due to {:send-sms? true, :sms-provider :link-mobility}."}
         (.getMessage e)))
    (is (= (ex-data e) {:reason {:sms-provider :link-mobility :send-sms? true}
                        :present #{:link-mobility/username :link-mobility/password}
                        :missing #{:link-mobility/url}}))))

