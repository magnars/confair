# <img align="right" src="conair.jpg" width="200" height="100"> Confair

Confair is a configuration library for Clojure, with some pretty nifty features:

- Config as EDN, in dev and prod.

- Encrypted secrets in the git repo.

- Masked secrets when logging.

- Check in working local config, with easy overrides.

The dream is a world where developers can change configuration options, even
secret ones, without coordinating updates with every other developer on the
team. The dream is easily switching between environments locally, without
commenting in and out bundles of related config options. The dream is checking
in the prod config in a single readable EDN-file, instead of passing it
piecemeal via ENV-vars in Kubernetes secrets. The dream is not accidentally
sending all your secrets to Datadog.

Welcome to the dream.

## Hello, Confair

Okay, that last part might have been too much. I was channeling the spirit of
Nicolas Cage. Anyway, let's take a quick look at how it works.

Create a config file somewhere, for example `./config/dev-config.edn`:

```clj
{:spotify/api-token-url "https://accounts.spotify.com/api/token"
 :spotify/api-url "https://api.spotify.com"
 :spotify/client-id "my-api-client"
 :spotify/client-secret "3abdc"}
```

We want to check a fully working dev config into source control, but we don't
want to check in our client-secret for all to see. Let's encrypt it.

First, create a file with a secret, and make sure we don't check it in:

```sh
echo shhh-dont-tell-anyone > secrets/dev.txt
echo "secrets/*.txt" >> .gitignore
```

Second, let confair know about the secret with some metadata:

```clj
^{:config/secrets {:secret/dev [:config/file "./secrets/dev.txt"]}}
{:spotify/api-token-url "https://accounts.spotify.com/api/token"
 :spotify/api-url "https://api.spotify.com"
 :spotify/client-id "my-api-client"
 :spotify/client-secret "3abdc"}
```

Now that confair knows where to find the secret, it's time to fire up the REPL
to encrypt the client-secret.

```clj
(require '[confair.config :as config])
(require '[confair.config-admin :as ca])

(ca/conceal-value (config/from-file "./config/dev-config.edn")
                  :secret/dev
                  :spotify/client-secret)
```

This loads the configuration (including the metadata we need), and uses the
`:secret/dev` secret to encrypt `:spotify/client-secret`. Our file has now
been updated to look like this:

```clj
^{:config/secrets {:secret/dev [:config/file "./secrets/dev.txt"]}}
{:spotify/api-token-url "https://accounts.spotify.com/api/token"
 :spotify/api-url "https://api.spotify.com"
 :spotify/client-id "my-api-client"
 :spotify/client-secret [:secret/dev "TlBZD.....kc="]}
```

Our client-secret has been encrypted with high-strength AES128, courtesy of
[Nippy](https://github.com/ptaoussanis/nippy). This file can now be safely
checked into source control. The dev config secret needs to be shared with
other developers out of band, but only once.

In order to use this config in our app, we read it back in like this:

```clj
(require '[confair.config :as config])

(def config (config/from-file "./config/dev-config.edn"))

(:spotify/client-id config) ;; => "my-api-client"
(:spotify/client-secret config) ;; => "3abdc"
```

Note that the secret is decrypted for us.

### Refs

In the preceding examples, you've seen this:

```clj
^{:config/secrets {:secret/dev [:config/file "./secrets/dev.txt"]}}
```

The `[:config/file ...]` part is a reference to content to be found on disk
somewhere, which is then loaded by confair. The other option is `[:config/env
...]` which reads its contents from an environment variable.

```clj
^{:config/secrets {:secret/dev [:config/env "MY_SECRET"]}}
```

When reading from disk, confair will trim the string, since newlines have a
tendency to be inserted by various editors.

Note that refs can also be used for config values, like so:

```clj
{:host-ip [:config/env "HOST_IP"]}
```

This can be useful in prod, where you might not know all configuration options
statically.

### Masking

What if you're sending logs to some log aggregation service? Maybe you are
logging your config when starting the process (this is a good idea), or maybe
you're adding config to the `request` map, and some middleware logs it when an
exception occurs?

In either case, you wouldn't want your secrets to be sent verbatim over the net.
Let's mask the config secrets:

```clj
(def config (-> (config/from-file "./config/dev-config.edn")
                (config/mask-config)))

(:spotify/client-id config) ;; => "my-api-client"
(:spotify/client-secret config) ;; => "3abdc"
```

You can still look up config keys individually, but if you turn the config map
into a string with `(str config)` or `(clojure.pprint/pprint config)` or
`(log/info config)` the secrets will be masked:

```clj
{:spotify/api-token-url "https://accounts.spotify.com/api/token"
 :spotify/api-url "https://api.spotify.com"
 :spotify/client-id "my-api-client"
 :spotify/client-secret [:config/masked-string "3*******"]}
```

Note that the masked config value is partly revealed to help with verification
from the logs.

### Local overrides

Have you ever made local configuration changes that you didn't mean to check in?
And then had to do the dance every time you commited something? And then a few
days later discovered you at some point checked them in anyway? There's a better
way.

We'll keep our local config in a file that isn't checked in.

```sh
echo "config/local-config.edn" >> .gitignore
```

We'll give the checked in defaults a better name:

```sh
mv ./config/dev-config.edn ./config/dev-defaults.edn
```

And create `./config/local-config.edn` which imports the defaults:

```clj
^{:config/secrets {:secret/dev [:config/file "./secrets/dev.txt"]}
  :config/import ["./config/dev-defaults.edn"]}
{:spotify/api-url "https://api-test.spotify.com"}
```

In this example, the configuration from `dev-defaults.edn` is imported, and then
`:spotify/api-url` is overridden.

Add a sample file for new developers for good measure:

```
cp config/local-config.edn config/local-config.edn.sample
```

And you are good to go!

## Install

Confair is a stable library - it will never change it's public API in breaking
way, and will never (intentionally) introduce other breaking changes.

With tools.deps:

```clj
com.magnars/confair {:mvn/version "2022.04.25"}
```

With Leiningen:

```clj
[com.magnars/confair "2022.04.25"]
```

## API overview

Now that you know the gist of how confair works, here's a slightly terser
overview of all the available functions.

### `(confair.config/from-file path)`

Loads configuration from a given file.

```clj
(def config (config/from-file "./config/dev-config.edn"))
```

Optionally takes overrides:

```clj
(def config (config/from-file "./config/dev-config.edn"
                              {:secrets {:secret/dev "foo"}
                               :refs {[:config/env "MY_IP"] "1.2.3.4"}}))
```

Note that `config/from-file` supports `:config/import` metadata to load
configuration from other files. It does not support recursive imports, mainly
because I think it's a bad idea.

### `(confair.config/from-env env-var)`

Loads configuration from a given env-variable.

```clj
(def config (config/from-env "MY_CONFIG"))
```

Like `from-file`, it takes an optional map of overrides. Unlike `from-file`, it
does not support `:config/import` metadata.

There is also a `confair.config/from-base64-env` available, which base64 decodes
the string in the environment variable prior to reading it.

### `(confair.config/from-string s source)`

If you're reading your configuration from somewhere other than the file system
or environment variables, this is the fallback you're looking for.

```clj
(def config (config/from-string (redis/get "my-app-config") "redis/my-app-config"))
```

The `source` parameter is used only for information. It is included in
exceptions, to give you an idea where to look for errors.

Like `from-file`, it takes an optional map of overrides. Unlike `from-file`, it
does not support `:config/import` metadata.

There is also a `confair.config/from-base64-string` available, which base64
decodes the string prior to reading it.

### `(confair.config/mask-config config)`

Replaces a configuration map with a `confair.config.MaskedConfig` map-like object,
that will mask secret keys when printed.

```clj
(def config (-> (config/from-env "MY_CONFIG")
                (config/mask-config))
(log/info "Starting app with config" config)
```

Note that `mask-config` will only mask those keys that are encrypted. It gets
this information from the metadata added by the various `config/from-*`
functions.

### `(confair.config/verify-required-together config key-bundles)`

This is a little helper function to give nice exceptions when a bunch of keys
are required together. It's an all-or-nothing kind of deal.

```clj
(config/verify-required-together config
  #{#{:datadog/host
      :datadog/port}
    #{:positionstack/api-key
      :positionstack/base-url}})
```

In this example, you can leave the `:datadog` or `:positionstack` config options
out, but if you include one, we're going to need both.

This function will either throw an exception or return the config unharmed, so
that you can use it in a threading.

### `(confair.config/verify-dependent-keys config kv-spec->required-keys)`

This is a little helper function to give nice exceptions when the value of one
key means other keys are also required.

```clj
(config/verify-dependent-keys config
  {{:sms-provider #{:twilio}} #{:twilio/account-id
                                :twilio/secret}
   {:sms-provider #{:link-mobility}} #{:link-mobility/url
                                       :link-mobility/username
                                       :link-mobility/password}})
```

In this example, different keys are required depending on if `:sms-provider` is
`:twilio` or `:link-mobility`.

You can add more clauses to the key, like this:

```clj
(config/verify-dependent-keys config
  {{:sms-provider #{:twilio}
    :send-smses? boolean} #{:twilio/account-id
                            :twilio/secret}})
```

In this example, we will only require the `:twilio/account-id` and
`:twilio/secret` keys if `:sms-provider` is `:twilio` *and* `:send-smses?` is
truthy.

This function will either throw an exception or return the config unharmed, so
that you can use it in a threading.

### `(confair.config-admin/conceal-value config secret-key key)`

This will rewrite a config file using `secret-key` to encrypt the value for `key`.

```clj
(ca/conceal-value (config/from-file "./config/dev-config.edn")
                  :secret/dev
                  :spotify/client-secret)
```

This uses the metadata added by `config/from-file` to locate
`:spotify/client-secret` on the file system, and uses the `:secret/dev` secret
to encrypt it.

If you want to encrypt nested values, you can pass in a `[:path :to :the
:value]` instead of just a `key`. Confair supports nested values in *maps* and
*vectors*.

### `(confair.config-admin/reveal-value config key)`

This will rewrite a config file, decrypting the value for `key`.

```clj
(ca/reveal-value (config/from-file "./config/dev-config.edn")
                 :spotify/client-secret)
```

If you have nested encrypted values, you can pass in a `[:path :to :the :value]` instead of just a `key`.

### `(confair.config-admin/replace-secret {:files :secret-key :old-secret :new-secret})`

This takes a set of files, and will re-encrypt all secrets using a new secret.

```clj
(ca/replace-secret {:files (ca/find-files "./config/prod/" #".edn$")
                    :secret-key :secret/prod
                    :old-secret [:config/file "./secrets/prod.txt"]
                    :new-secret "foo"})
```

In this case we use the utility function `config.admin/find-files` to find all
edn-files in the `config/prod`-directory, and for all keys encrypted with the
`:secret/prod` secret, we re-encrypt it with the secret `"foo"`.

In the example, the old secret is read from disk, while the new secret is
included inline. You can mix and match these freely. Just make sure you don't
check the secret in. :)

## License

Copyright © 2021-2022 Magnar Sveen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
