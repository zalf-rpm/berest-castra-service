;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns de.zalf.berest.web.castra.rules
  (:refer-clojure :exclude [assert])
  (:require [castra.core :refer [ex *request* *session*]]
            [de.zalf.berest.core.datomic :as db]))

;;; utility ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro assert [expr & [msg]]
  `(when-not ~expr (throw (ex (or ~msg "Server error.") {}))))

;;; internal ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(defn get-pwd [db-val user] (get-in db-val [:users user :pass]))
(defn do-login! [cred] (swap! *session* assoc :user cred))

;;; public ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn allow       []      (constantly true))
(defn deny        []      (throw (ex "Permission denied." {})))
(defn logout!     []      (swap! *session* assoc :user nil))
(defn logged-in?  []      (or (get @*session* :user)
                              (throw (ex #_{:state nil} "Please log in." {}))))

#_(defn register! [user pwd1 pwd2]
  (assert (= pwd1 pwd2) "Passwords don't match.")
  (swap! db #(do (assert (available? % user) "Username not available.")
                 (assoc-in % [:users user] {:pass pwd1})))
  (do-login! user))

(defn login! [user pwd]
  (let [cred (db/credentials* (db/current-db) user pwd)]
    (assert (not (nil? cred)) "Bad username/password.")
    (do-login! cred)))
