(ns webapp.resources
  "Helpful extensions to liberator."
  (:require [cemerick.friend :as friend]
            [compojure.route :as route]
            [liberator.conneg :as conneg]
            [liberator.core :as l]
            [liberator.representation :as rep]
            [webapp.db :as db]
            [ring.util.response :as r]
            [clojure.string :as str])
  (:import [liberator.representation RingResponse]))

(defn getUser [request]
  "Gets the user from the request"
  (let [authentications (get-in request [:session :cemerick.friend/identity :authentications])
        current (get-in  request [:session :cemerick.friend/identity :current])]
    (get-in authentications [current])))

(defn merge-with-map [merge-fns & maps]
  "Returns a map that consists of the rest of the maps conj-ed onto
  the first.  If a key occurs in more than one map, the mapping(s)
  from the latter (left-to-right) will be combined with the mapping in
  the result by looking up the proper merge function and in the
  supplied map of key -> merge-fn and using that for the big merge. If
  a key doesn't have a merge function, the right value wins (as with
  merge)."
  (when (some identity maps)
    (let [merge-entry (fn [m e]
                        (let [k (key e) v (val e)]
                          (if-let [f (and (contains? m k) (merge-fns k))]
                            (assoc m k (f (get m k) v))
                            (assoc m k v))))
          merge2 (fn [m1 m2] (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge2 maps))))

(defn flatten-resource [kvs]
  "Accepts a map (or a sequence, which gets turned into a map) of
  resources; if the map contains the key :base, the kv pairs from THAT
  map are merged in to the current map. If there are clashes, the new
  replaces the old by default.
  Combat this by supplying a :merge-with function in the map. This key
  should point to a map from keyword -> binary function; this function
  will be used to resolve clashes."
  (let [m (if (map? kvs) kvs (apply hash-map kvs))
        trim #(dissoc % :base)]
    (if-let [base (:base m)]
      (let [combined (flatten-resource base)
            trimmed (trim m)]
        (if-let [merger (if (contains? m :merge-with)
                          (:merge-with m)
                          (:merge-with combined))]
          (if (fn? merger)
            (merge-with merger combined trimmed)
            (merge-with-map merger combined trimmed))
          (merge combined trimmed)))
      (trim m))))

(defn resource [& kvs]
  "Functional version of defresource. Takes any number of kv pairs,
  returns a resource function."
  (fn [request] (l/run-resource request (flatten-resource kvs))))

(defmacro defresource [name & kvs]
  "The same as liberator's defresource, except it allows for a base
  resource and a merge-with function."
  (if (vector? (first kvs))
    (let [[args & kvs] kvs]
      `(defn ~name [~@args]
         (resource ~@kvs)))
    `(defn ~name [req#]
       (l/run-resource req# (flatten-resource [~@kvs])))))

;; ## Utilities

(defn accepted-types [req]
  "Returns a sequence of content types accepted by the supplied
  request. If no accept header is present, returns nil."
  (when-let [accepts-header (get-in req [:headers "accept"])]
    (->> (conneg/sorted-accept accepts-header ["*/*"])
         (map (comp conneg/stringify :type))
         (filter not-empty))))

(defn get-media
  "Pulls the media type out of the request, or parses it from the
  content headers.
  allowed-types is a set containing pairs (e.g., [\"text\" \"*\"])
  or strings (e.g., \"text/plain\").
  If no allowed-types is present, returns the type most favored by the
  client."
  ([req] (first (accepted-types req)))
  ([req allowed-types]
   {:pre [(contains? (:headers req) "accept")
          (sequential? allowed-types)]}
   (l/try-header "Accept"
                 (when-let [accept-header (get-in req [:headers "accept"])]
                   (let [type (conneg/best-allowed-content-type accept-header allowed-types)]
                     (not-empty (conneg/stringify type)))))))

(def ring-response rep/ring-response)
(def ringify (comp rep/ring-response r/response))

(defn to-response [t req]
  "to-response does more intelligent response parsing on your
  liberator ring responses.
  Liberator tries to coerce your returned value into the proper
  content type; maps get turned into json or clojure as required, etc.
  The problem with this, naively, is that ring's responses are ALSO
  just bare maps. If you return a bare map to a text/html request,
  liberator tries to coerce the map into HTML.
  The liberator solution is a special wrapper type called RingResponse
  that gets passed through without diddling. This function handles the
  most common response type cases in one spot
  If you pass in an instance of RingResponse, to-response passes it
  through untouched.
  If you pass in a ring response map, it's wrapped in an instance of
  RingResponse and passed on (and ignored by liberator).
  else, liberator tries to coerce as before."
  (cond (instance? RingResponse t) t
        (r/response? t) (rep/ring-response t)
        :else (rep/ring-response (rep/as-response t req))))

(defn generic [data req media-type]
  "If you pass a response back to liberator before it's parsed the
  content type it freaks out and says that it can't dispatch on
  null. This generic method calls the proper multimethod rendering on
  json, clojure, etc, all of that business, before passing the result
  back up the chain through liberator."
  (to-response data (assoc-in req [:representation :media-type] media-type)))

(defn media-typed [& ms]
  "Accepts a map of encoding -> handler (which can be a constant or a
  function) and returns a NEW handler that delegates properly based on
  the request's encoding. If no encoding is found, calls the handler
  under the :default key."
  (let [m (apply merge ms)]
    (fn [req]
      (let [get-media #(get-in % [:representation :media-type])
            parsed-type (get-media req)
            media-type (or parsed-type (get-media (l/negotiate-media-type req)))]
        (when-let [handler (get m media-type (:default m))]
          (if (fn? handler)
            (handler req)
            (if-not (= parsed-type media-type)
              (generic handler req media-type)
              (to-response handler req))))))))

(defn with-default [default-type m]
  "Duplicates the entry under the supplied media-type as the default
  in the supplied response map."
  (if-let [response (m default-type)]
    (assoc m :default response)
    m))

;; ## The Good Stuff

(def base-resource
  "Base for all resources.
  Due to the way liberator's resources merge, these base definitions
  define a bunch of content types, even if the resources that inherit
  from them don't. The defaults are here to provide reasonable text
  error messages, instead of returning big slugs of html."
  (let [not-found (comp rep/ring-response (route/not-found "Route not found!"))
        base {"text/html" not-found}]
    {:handle-not-acceptable
     (->> {"application/json" {:success false
                               :message "No acceptable resource available"}
           "application/edn" {:success false
                              :message "No acceptable resource available"}
           "text/plain" "No acceptable resource available."}
          (with-default "text/plain")
          (media-typed base))
     :handle-not-found
     (->> {"application/json" {:success false
                               :message "Resource not found."}
           "application/edn" {:success false
                              :message "Resource not found."}
           "text/plain" "Resource not found."}
          (with-default "text/plain")
          (media-typed base))}))

;; ## Base Resources
;;
;; These functions and vars provide base resources that make it easier
;; to define new liberator resources.

(defn friend-auth [auth-fn] 
  "Returns a base resource that authenticates using the supplied
  auth-fn. Authorization failure will trigger Friend's default
  unauthorized response."
  {:base base-resource :authorized? auth-fn})

(def authenticated-base
  "Returns a base resource that authenticates users against the
  supplied set of roles."
  (friend-auth (comp boolean friend/identity :request)))

(defn hasMultiDataAndUserAccess? [request]
  "checks whether userId is in the list of userIds on the retrieved resources"
  (let [userIdentity           (getUser request)
        userId                 (:id userIdentity)
        
        resourceIdRead         (read-string (last (str/split (:uri request) #"/")))
        resourceId             (if (number? resourceIdRead) resourceIdRead nil)
        
        threadIdStr            (get (:query-params request) "threadId")
        threadId               (if (not (nil? threadIdStr)) (Long/parseLong threadIdStr) nil)
        
        resourceEntityId       (if (not (nil? resourceId)) (db/read resourceId))
        resource               (if (not (nil? resourceEntityId)) (db/expand resourceEntityId) nil)
        resourceByIdOrThreadId (if (not (nil? threadId)) (db/expand (db/read threadId)) resource)
        
        authorizedUser         (boolean (friend/identity request))
        userIdsOnResource      (if (not (nil? resourceByIdOrThreadId)) (:userIds resourceByIdOrThreadId) [-1000])
        allowed?               (and (not (= [-1000] userIdsOnResource)) (some #(= userId %) userIdsOnResource) authorizedUser)]
    allowed?))

(def data-multi-userId-auth
  (friend-auth (comp boolean hasMultiDataAndUserAccess? :request)))
