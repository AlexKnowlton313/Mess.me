(defproject Mess.me "0.1.0"
  :description "a small little messenger app ripped from a summer project"
  :url ""
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]
                 [compojure "1.4.0"]
                 [http-kit "2.0.0"]
                 [com.taoensso/sente "1.10.0"]
                 [com.taoensso/timbre "4.7.2"]
                 [com.cognitect/transit-clj  "0.8.285"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 [cljs-http "0.1.39"]
                 [clj-time "0.11.0"]
                 [liberator "0.14.0"]
                 [fogus/ring-edn "0.3.0"]
                 [clj-json "0.5.3"]
                 [com.andrewmcveigh/cljs-time "0.4.0"]
                 [com.cemerick/url "0.1.1"]
                 [hiccup "1.0.5"]
                 [reagent "0.5.1"]
                 [com.cemerick/friend "0.2.1" :exclusions [org.clojure/core.cache]]
                 [cljsjs/material "1.1.1-0"]
                 [prismatic/schema "1.0.4"]
                 [com.datomic/datomic-free "0.9.5344"]]
  
  :main webapp.server
  
  :plugins [[lein-cljsbuild "1.1.2"]
            [lein-figwheel "0.5.0-4"]]
  :source-paths ["src"]
  :cljsbuild {:builds [{:id "webapp"
                        :figwheel {:on-jsload "webapp.main/fig-reload"}
                        :source-paths ["src-cljs"]
                        :compiler {:output-to "resources/public/js/main.js"
                                   :output-dir "resources/public/js"
                                   :optimizations :none
                                   :source-map true
                                   :parallel-build false
                                   :asset-path "js"
                                   :main "webapp.main"}}]}
  :clean-targets ^{:protect false} [:target-path :compile-path
                                    "resources/public/js"]
  
  :figwheel {:css-dirs ["resources/public/css"]}
  :global-vars {*print-length* 20}
  :profiles
  {:uberjar {:aot :all
             :cljsbuild {:builds [{:source-paths ["src/cljs"]
                                   :compiler {:output-to "resources/public/js/script.js"
                                              :optimizations :simple
                                              :pretty-print false}}]}}})
