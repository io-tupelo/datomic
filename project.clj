(defproject io.tupelo/datomic "19.12.01"
  :description "Tupelo Datomic:  Datomic With A Spoonful of Honey"
  :url "https://github.com/io-tupelo/datomic"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
                 [org.clojure/clojure "1.10.1"]
                 [prismatic/schema "1.1.12"]
                 [tupelo "0.9.182"]
                 ]
  :resource-paths ["resources/"
                   "resources/datomic-free-0.9.5661-everything.jar"
                  ]
  :profiles { :dev {:dependencies []
                   :plugins [] }
             :uberjar {:aot :all}}

  :global-vars { *warn-on-reflection* false }

; :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
;                                  :creds :gpg}}

  ;:plugins  [ [lein-codox "0.10.7"] ]
  ;:codox {:src-dir-uri "http://github.com/cloojure/tupelo-datomic/blob/master/"
  ;        :src-linenum-anchor-prefix "L"}

  :deploy-repositories {  "snapshots" :clojars
                          "releases"  :clojars 
                          :sign-releases false }

  :update :daily ;  :always
; :main ^:skip-aot tupelo-datomic.core

  :target-path "target/%s"
  :clean-targets [ "target" ]

  ; "lein test"         will not  run tests marked with the ":slow" metadata
  ; "lein test :slow"   will only run tests marked with the ":slow" metadata
  ; "lein test :all"    will run all  tests (built-in)
  :test-selectors { :default    (complement :slow)
                    :slow       :slow }

  :jvm-opts ["-Xms500m" "-Xmx2g"
            ]
)
