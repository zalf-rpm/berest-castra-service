(ns de.zalf.berest.web.castra.api
  (:require [castra.core :as cc :refer [defrpc ex *session*]]
            [castra.middleware :as cm]
            [cognitect.transit :as transit]

            [de.zalf.berest.web.castra.rules :as rules]
            [de.zalf.berest.core.data :as data]
            [de.zalf.berest.core.util :as util]
            [de.zalf.berest.core.api :as api]
            [de.zalf.berest.core.datomic :as db]
            [de.zalf.berest.core.climate.climate :as climate]
            [de.zalf.berest.core.import.dwd-data :as import-dwd]
            [de.zalf.berest.core.import.zalf-climate-data :as import-csv]
            [datomic.api :as d]
            [simple-time.core :as time]
            [clj-time.core :as ctc]
            [clj-time.format :as ctf]
            [clj-time.coerce :as ctcoe]
            [clojure-csv.core :as csv]
            [de.zalf.berest.core.core :as bc]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pp])
  (:import (java.io ByteArrayOutputStream)))

(def entity-map-writer
  (transit/write-handler
    (constantly "map")
    (fn [em] (into {:db/id (:db/id em)} (for [[k v] em] [k v])))))

(reset! cm/clj->json
        #(let [out (ByteArrayOutputStream. 4096)]
          (transit/write (transit/writer out :json
                                         {:handlers {datomic.query.EntityMap entity-map-writer}})
                         %2)
          (.toString out)))

;;; utility ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn call      [f & args]  (apply f args))
(defn apply-all [fns coll]  (mapv #(%1 %2) (cycle fns) coll))
(defn every-kv? [fn-map m]  (->> m (merge-with call fn-map) vals (every? identity)))
(defn map-kv    [kfn vfn m] (into (empty m) (map (fn [[k v]] [(kfn k) (vfn v)]) m)))
(defn map-k     [kfn m]     (map-kv kfn identity m))
(defn map-v     [vfn m]     (map-kv identity vfn m))

;;; internal ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(defn new-message [db-val from conv text]
  {:from from, :conv conv, :text text})

#_(defn add-message [db-val from conv text]
  (let [cons* #(cons %2 (or %1 '()))]
    (update-in db-val [:messages conv] cons* (new-message db-val from conv text))))

(defn get-farms
  [db user-id]
  (map #(select-keys % [:farm/id :farm/name]) (data/db->a-users-farms db user-id)))

(defn get-plots
  [db farm-id]
  (map #(select-keys % [:plot/id :plot/name]) (data/db->a-farms-plots db farm-id)))



(def state-template
  {:language :lang/de

   :farms nil

   ;will only be available if user has role #admin
   :users nil

   :weather-stations {}

   :full-selected-weather-stations {}

   :technology nil #_{:technology/cycle-days 1
                      :technology/outlet-height 200
                      :technology/sprinkle-loss-factor 0.4
                      :technology/type :technology.type/drip ;:technology.type/sprinkler
                      :donation/min 1.0
                      :donation/max 30.0
                      :donation/opt 20.0
                      :donation/step-size 5.0}

   #_:plot #_{:plot/stt 6212
          :plot/slope 1
          :plot/field-capacities []
          :plot/fc-pwp-unit :soil-moisture.unit/volP
          :plot/permanent-wilting-points []
          :plot/ka5-soil-types []
          :plot/groundwaterlevel 300}

   :user-credentials nil})

(def static-state-template
  {:stts nil
   :slopes nil
   :substrate-groups nil
   :ka5-soil-types nil
   :crop->dcs nil})


;;; public ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- stem-cell-state
  [db {user-id :user/id :as cred}]
  (let [farms-with-plots
        (into {} (map (fn [{farm-id :farm/id
                            :as farm}]
                        [farm-id (assoc farm :plots (into {} (map (juxt :plot/id identity)
                                                                  (data/db->a-farms-plots db farm-id))))])
                      (data/db->a-users-farms db user-id)))]
    (assoc state-template :farms farms-with-plots
                          :users (when ((:user/roles cred) :user.role/admin)
                                   (data/db->all-users db))
                          :weather-stations (data/db->a-users-weather-stations db user-id)
                          :user-credentials cred)))

(defn- static-stem-cell-state
  [db {user-id :user/id :as cred}]
  (assoc static-state-template :stts (data/db->all-stts db)
                               :slopes (data/db->all-slopes db)
                               :substrate-groups (data/db->all-substrate-groups db)
                               :ka5-soil-types (data/db->all-ka5-soil-types db)
                               :crop->dcs (data/db->all-crop->dcs db)
                               :all-weather-stations (data/db->all-weather-stations db)
                               :minimal-all-crops (data/db->min-all-crops db user-id)))

(defrpc get-berest-state
  [& [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (stem-cell-state db cred))))

(defrpc get-static-state
  "returns static state which usually won't change once it's on the client"
  [& [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (static-stem-cell-state db cred))))


#_(defrpc get-minimal-all-crops
  "returns the minimal version of all crops, a list of
  [{:crop/id :id
    :crop/name :name
    :crop/symbol :symbol}]
    currently"
  [& [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (map #(select-keys % [:crop/id :crop/name :crop/symbol])
           (data/db->min-all-crops db)))))




(defrpc get-state-with-full-selected-crops
  [selected-crop-ids & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred  (if user-id
                (db/credentials* db user-id pwd)
                (:user @*session*))

        crops (data/db->full-selected-crops db selected-crop-ids)]
    (when cred
      (assoc (stem-cell-state db cred)
        :full-selected-crops (into {} (map (fn [c] [(:crop/id c) c]) crops))))))

(defn get-weather-station-data*
  [db weather-station-id years]
  (->> years
       (map (fn [year]
              (let [data (:data (climate/weather-station-data db year weather-station-id))
                    data* (map #(select-keys % [:db/id
                                                :weather-data/date
                                                :weather-data/global-radiation
                                                :weather-data/average-temperature
                                                :weather-data/precipitation
                                                :weather-data/evaporation
                                                :weather-data/prognosis-date]) data)
                    data** (filter (comp not :weather-data/prognosis-date) data*)]
                [year data**])),,,)
       (into {},,,)
       (#(assoc {} :weather-station-id weather-station-id
                   :data %),,,)))

(defrpc get-weather-station-data
  [weather-station-id years & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (get-weather-station-data* db weather-station-id years)
        (catch Exception e
          (throw (ex (str "Couldn't get data from weather station with id: " weather-station-id "!") {}))))
      )))

(defrpc import-weather-data
        [weather-station-id csv-data & {:keys [user-id pwd] :as opts}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              opts* (merge {:separator \tab
                            :decimal-separator :dot
                            :ignore-lines 0
                            :element-order [:date :precip :evap]
                            :date-format "dd.MM.yyyy"}
                           opts)]
          (when cred
            (try
              (import-csv/import-hoplon-client-csv-data (db/connection) (:user/id cred) weather-station-id csv-data opts*)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't import weather data!" {})))))))

(defrpc get-crop-data
  [crop-id & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (first (data/db->full-selected-crops db [crop-id])))))

(defrpc create-new-user
  [new-user-id new-user-pwd & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (db/register-user (db/connection) new-user-id new-user-pwd new-user-id [:guest])
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new farm!" {})))))))

(defrpc set-new-password
  [pwd-user-id new-user-pwd & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (db/update-password (db/connection) pwd-user-id new-user-pwd)
        (catch Exception e
          (throw (ex "Couldn't update password!" {})))))))

(defrpc update-user-roles
  [update-user-id new-roles & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (db/update-user-roles (db/connection) update-user-id new-roles)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't update user to new roles!" {})))))))

(defrpc add-user-weather-stations
  [update-user-id new-weather-station-ids & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (db/add-user-weather-stations (db/connection) update-user-id new-weather-station-ids)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't add new user weather-stations!" {})))))))

(defrpc remove-user-weather-stations
  [update-user-id weather-station-ids & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (db/remove-user-weather-stations (db/connection) update-user-id weather-station-ids)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't remove user weather-stations!" {})))))))

(defrpc create-new-farm
  [temp-farm-name & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-farm (db/connection) (:user/id cred) temp-farm-name)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new farm!" {})))))))

(defrpc create-new-local-user-weather-station
        [temp-weather-station-name local? & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              (data/create-new-local-user-weather-station (db/connection) (:user/id cred) temp-weather-station-name :local? local?)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't create new weather-station!" {})))))))



(defrpc create-new-plot
  [farm-id & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-plot (db/connection) (:user/id cred) farm-id)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new plot!" {})))))))

(defrpc create-new-plot-annual
  [plot-id new-year copy-data? copy-annual-id & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-plot-annual (db/connection) (:user/id cred) plot-id new-year copy-data? copy-annual-id)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new plot annual!" {})))))))

(defrpc create-new-farm-address
  [farm-id & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-farm-address (db/connection) (:user/id cred) farm-id)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new farm address!" {})))))))

(defrpc create-new-farm-contact
        [farm-id & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              (data/create-new-farm-contact (db/connection) (:user/id cred) farm-id)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't create new farm contact!" {})))))))

(defrpc create-new-soil-data-layer
  [id-attr id depth type value & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-soil-data-layer (db/connection) (:user/id cred)
                                         id-attr id (int depth) type (case type
                                                                       (:pwp :fc :sm :ism) (double value)
                                                                       :ka5 value))
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new fc, pwp or ka5 layer!" {})))))))

(defrpc create-new-donation
  [annual-plot-entity-id abs-start-day abs-end-day amount & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-donation (db/connection) (:user/id cred) annual-plot-entity-id
                                  (int abs-start-day) (int abs-end-day) (double amount))
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new donation!" {})))))))

(defrpc create-new-soil-moisture
        [annual-plot-entity-id & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              (data/create-new-soil-moisture (db/connection) (:user/id cred) annual-plot-entity-id)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't create new soil-moistures entity!" {})))))))

(defrpc create-new-crop-instance
  [annual-plot-entity-id crop-template-id & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-crop-instance (db/connection) (:user/id cred) annual-plot-entity-id crop-template-id)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new crop instance!" {})))))))

(defrpc create-new-dc-assertion
  [crop-instance-entity-id abs-dc-day dc #_at-abs-day & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/create-new-dc-assertion (db/connection) (:user/id cred) crop-instance-entity-id
                                      (int abs-dc-day) (int dc) #_(int at-abs-day))
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't create new dc assertion!" {})))))))

(defrpc create-new-weather-data
        [id-attr id date tavg globrad evap precip prog-date & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              #_(println "(create-new-weather-data " id-attr " " id " " date " " tavg " " globrad " " evap
                       " " precip " " prog-date ")")
              (data/create-new-weather-data (db/connection) (:user/id cred)
                                            id-attr id date tavg globrad evap precip prog-date)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't create new weather-data!" {})))))))

(defrpc create-new-com-con
        [contact-entity-id com-con-id com-con-desc com-con-type & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              (data/create-new-com-con (db/connection) (:user/id cred) contact-entity-id
                                       com-con-id com-con-desc com-con-type)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't create new communication connection!" {})))))))

(defrpc create-new-crop
        [temp-name & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              (data/create-new-crop (db/connection) (:user/id cred) temp-name)
              (static-stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex "Couldn't create new crop!" {})))))))

(defrpc copy-crop
        [crop-id temp-name & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))]
          (when cred
            (try
              (data/copy-crop (db/connection) (:user/id cred) crop-id temp-name)
              (static-stem-cell-state (db/current-db) cred)
              (catch Exception e
                (throw (ex (str "Couldn't copy crop with id: " crop-id "!") {})))))))

(defrpc delete-crop
        [crop-db-id & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              tx-data [[:db.fn/retractEntity crop-db-id]]]
          (when cred
            (try
              (d/transact (db/connection) tx-data)
              (static-stem-cell-state (db/current-db) cred)
              (catch Exception e
                (log/info "Couldn't delete crop entity! tx-data:\n" tx-data)
                (throw (ex (str "Couldn't delete crop!") {})))))))


(defrpc update-db-entity
  [entity-id attr value & {:keys [user-id pwd value-type]
                           :or {value-type :identity}}]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))

        value* (case value-type
                 :double (double value)
                 :int (int value)
                 value)

        tx-data [[:db/add entity-id (d/entid db attr) value*]]]
    (when cred
      (try
        (d/transact (db/connection) tx-data)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (log/info "Couldn't update entity! tx-data:\n" tx-data)
          (throw (ex (str "Couldn't update entity!") {})))))))

(defrpc delete-db-entity
  [entity-id?s & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))

        entity-ids (if (sequential? entity-id?s) entity-id?s [entity-id?s])

        tx-data (for [e-id entity-ids]
                  [:db.fn/retractEntity e-id])]
    (when cred
      (try
        (d/transact (db/connection) tx-data)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (log/info "Couldn't retract entity! tx-data:\n" tx-data)
          (throw (ex (str "Couldn't delete entity!") {})))))))

(defrpc retract-db-value
        [entity-id attr value & {:keys [user-id pwd value-type]
                                 :or {value-type :identity}}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              value* (case value-type
                       :double (double value)
                       :int (int value)
                       value)

              tx-data [[:db/retract entity-id (d/entid db attr) value*]]]
          (when cred
            (try
              (d/transact (db/connection) tx-data)
              (stem-cell-state (db/current-db) cred)
              (catch Exception e
                (log/info "Couldn't retract information on entity! tx-data:\n" tx-data)
                (throw (ex (str "Couldn't delete information on entity!") {})))))))



(defrpc update-crop-db-entity
        [crop-id entity-id attr value & {:keys [user-id pwd value-type]
                                         :or {value-type :identity}}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              value* (case value-type
                       :double (double value)
                       :int (int value)
                       value)

              tx-data [[:db/add entity-id (d/entid db attr) value*]]
              ;_ (println "tx-data: " (pr-str tx-data))
              ]
          (when cred
            (try
              (d/transact (db/connection) tx-data)
              (first (data/db->full-selected-crops (db/current-db) [crop-id]))
              (catch Exception e
                (log/info "Couldn't update entity! tx-data:\n" tx-data)
                (throw (ex (str "Couldn't update entity!") {})))))))

(defrpc delete-crop-db-entity
        [crop-id entity-id?s & [user-id pwd]]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              entity-ids (if (sequential? entity-id?s) entity-id?s [entity-id?s])

              tx-data (for [e-id entity-ids]
                        [:db.fn/retractEntity e-id])]
          (when cred
            (try
              (d/transact (db/connection) tx-data)
              (first (data/db->full-selected-crops (db/current-db) [crop-id]))
              (catch Exception e
                (log/info "Couldn't retract entity! tx-data:\n" tx-data)
                (throw (ex (str "Couldn't retract entity! tx-data:\n" tx-data) {})))))))

(defrpc create-new-crop-kv-pair
        [crop-id crop-attr key-attr key-value value-attr value-value & {:keys [user-id pwd value-type]
                                                                        :or {value-type :identity}}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              value-value* (case value-type
                             :double (double value-value)
                             :int (int value-value)
                             value-value)
              ]
          (when cred
            (try
              (data/create-new-crop-kv-pair (db/connection) (:user/id cred) crop-id
                                            crop-attr key-attr key-value value-attr value-value*)
              (first (data/db->full-selected-crops (db/current-db) [crop-id]))
              (catch Exception e
                (throw (ex (str "Couldn't create new key-value pair for crop with id: " crop-id "!") {})))))))

(defrpc set-substrate-group-fcs-and-pwps
  [plot-id substrate-group-key & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))]
    (when cred
      (try
        (data/set-substrate-group-fcs-and-pwps db (db/connection) (:user/id cred) plot-id substrate-group-key)
        (stem-cell-state (db/current-db) cred)
        (catch Exception e
          (throw (ex "Couldn't set/copy substrate group's fcs/pws!" {})))))))


#_(defrpc update-weather-station
  [weather-station-id name lat lng & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))

        (d/q '[:find ?ws-e #_?year
               :in $ ?ws-id
               :where
               [?-e :user/id ?user-id]
               [?user-e :user/weather-stations ?ws-e]]
             db weather-station-id)

        ]
    (when cred
      (d/transact (db/connection)
                  [(when name [:db/add [:weather-station/id weather-station-id] :weather-station/name name])
                   (when lat [:db/add [:weather-station/id weather-station-id] :weather-station/g name])
                   ]))))


(defrpc login
        [user-id pwd]
        {:rpc/pre [(rules/login! user-id pwd)]}
        (println "login user-id: " user-id)
        (get-berest-state))

(defrpc rpc-login
        [user-id pwd]
        {:rpc/pre [(rules/login! user-id pwd)]}
        (println "rpc login user-id: " user-id)
        {:login-successful true})

(defrpc logout
  []
  {:rpc/pre [(rules/logout!)]}
  nil)

(defn calc-or-sim-csv
  [f plot-id until-date donations]
  (let [db (db/current-db)
        ud (ctcoe/from-date until-date)
        until-julian-day (.getDayOfYear ud)
        year (ctc/year ud)
        donations (for [{:keys [day month amount]} donations]
                    {:donation/abs-start-day (util/date-to-doy day month year)
                     :donation/abs-end-day (util/date-to-doy day month year)
                     :donation/amount amount})
        {:keys [inputs soil-moistures]} (f db plot-id until-julian-day 6 year donations [])]
    (->> soil-moistures
         (api/create-csv-output inputs ,,,)
         (#(csv/write-csv % :delimiter \tab) ,,,))))

(defrpc simulate-csv
  [plot-id until-date donations]
  {:rpc/pre [(rules/logged-in?)]}
  (calc-or-sim-csv api/simulate-plot-from-db plot-id until-date donations))

(defrpc calculate-csv
  [plot-id until-date donations]
  {:rpc/pre [(rules/logged-in?)]}
  (calc-or-sim-csv api/calculate-plot-from-db plot-id until-date donations))


(defrpc calculate-from-db
  [plot-id plot-annual-id calculation-doy & [user-id pwd]]
  {:rpc/pre [(nil? user-id)
             (rules/logged-in?)]}
  (let [db (db/current-db)

        cred (if user-id
               (db/credentials* db user-id pwd)
               (:user @*session*))

        prognosis-days 6

        donations (data/db->donations db plot-id plot-annual-id)
        ;_ (println "donations: " (pr-str donations))

        {:keys [inputs
                soil-moistures]
         :as res} (api/calculate-plot-from-db db plot-id plot-annual-id calculation-doy prognosis-days donations [])

        measured-soil-moistures (drop-last prognosis-days soil-moistures)
        ;prognosis-soil-moistures (take-last prognosis-days soil-moistures)

        ;inp (map (fn [i] (select-keys i [:abs-day :precipitation :evaporation])) inputs)
        ;_ (println "inputs: " (pr-str (take 5 inp)))
        ;sms (map (fn [sm] (select-keys sm [:abs-day :soil-moistures])) soil-moistures)
        ;_ (println "sms: " (pr-str (take 5 sms)))


        ;_ (println "res: " (pr-str res))

        {slope :plot/slope
         annuals :plot/annuals
         :as plot} (data/db->plot db plot-id)
        annual-for-id (first (filter #(=  plot-annual-id (:plot.annual/id %)) annuals))
        tech (:plot.annual/technology annual-for-id)

        recommendation (bc/calc-recommendation prognosis-days
                                               (:slope/key slope)
                                               tech
                                               (take-last prognosis-days inputs)
                                               (:soil-moistures (last measured-soil-moistures)))
        recommendation* (merge recommendation (bc/recommendation-states (:state recommendation)))
        ]
    (when cred
      {:recommendation recommendation*
       :soil-moistures soil-moistures
       :inputs (map #(select-keys % [:dc :abs-day :precipitation :evaporation
                                     :donation :profit-per-dt :avg-additional-yield-per-mm
                                     :qu-target :extraction-depth-cm :cover-degree :transpiration-factor
                                     :crop-id])
                    inputs)
       :crops (reduce (fn [m {:keys [crop]}]
                        (assoc m (:crop/id crop) crop))
                      {} inputs)})))

(defn calculate-from-remote-data*
  [db run-id crop-id {:keys [weather-data fcs-mm pwps-mm isms-mm ka5s lambdas layer-sizes slope dc-assertions]}]
  (binding [de.zalf.berest.core.core/*layer-sizes* (or layer-sizes (repeat 20 10))]
    (let [sorted-climate-data (into (sorted-map)
                                    (map (fn [[year years-data]]
                                           [year (into (sorted-map)
                                                       (for [[doy precip evap] years-data]
                                                         [doy {:weather-data/precipitation precip
                                                               :weather-data/evaporation evap}]))])
                                         weather-data))

          ;_ (println "sorted-climate-data: ")
          ;_ (pp/pprint sorted-climate-data)

          crop-template (data/db->crop-by-id (db/current-db) crop-id)

          ;plot (bc/deep-db->plot db #uuid "539ee6fc-762f-40ae-8c7d-7827ea61f709" 1994 #_"53a3f382-dae7-4fff-9d68-b3c7782fcae7" #_2014)

          plot** {:plot/ka5-soil-types ka5s

                  :plot/field-capacities fcs-mm
                  :plot/fc-pwp-unit :soil-moisture.unit/mm

                  :plot/permanent-wilting-points pwps-mm
                  :plot/pwp-unit :soil-moisture.unit/mm

                  :plot.annual/abs-day-of-initial-soil-moisture-measurement 90
                  :plot.annual/initial-soil-moistures isms-mm
                  :plot.annual/initial-sm-unit :soil-moisture.unit/mm

                  :lambdas lambdas

                  ;:plot.annual/crop-instances (:plot.annual/crop-instances plot)

                  :plot.annual/crop-instances [{:crop.instance/template crop-template
                                                ;:crop.instance/name "dummy name"
                                                :crop.instance/dc-assertions (for [[abs-day dc] dc-assertions]
                                                                               {:assertion/abs-assert-dc-day abs-day
                                                                                :assertion/assert-dc dc})}]

                  :fallow (data/db->crop-by-name db 0 :cultivation-type 0 :usage 0)

                  :plot.annual/technology {:donation/step-size 5.0,
                                           :donation/opt 20.0,
                                           :donation/max 30.0,
                                           :donation/min 5.0,
                                           :technology/type :technology.type/sprinkler,
                                           :technology/sprinkle-loss-factor 0.2,
                                           :technology/cycle-days 1}

                  :plot/slope {:slope/key 1
                               :slope/description "eben"
                               :slope/symbol "NFT 01" }

                  :slope slope

                  :plot.annual/donations []

                  :plot/damage-compaction-area 0.0
                  :plot/damage-compaction-depth 300
                  :plot/irrigation-area 1.0
                  :plot/crop-area 1.2
                  :plot/groundwaterlevel 300

                  ;:plot.annual/year 1994
                  }

          ;_ (println "plot**: ")
          ;_ (pp/pprint plot**)

          res (map (fn [[year sorted-weather-map]]
                     #_(println "calculating year: " year)
                     [year (let [inputs (bc/create-input-seq plot**
                                                             sorted-weather-map
                                                             365
                                                             []
                                                             (-> plot** :plot.annual/technology :technology/type))]
                             #_(println "inputs:")
                             #_(pp/pprint inputs)
                             (bc/calculate-sum-donations-by-auto-donations
                               inputs (:plot.annual/initial-soil-moistures plot**)
                               (int slope) #_(-> plot** :plot/slope :slope/key)
                               (:plot.annual/technology plot**)
                               5))])
                   sorted-climate-data)
          ;_ (println "res: " res)
          _ (println "calculated run-id: " run-id)
          ]
      {:run-id run-id
       :result (into {} res)}
      #_(mapv second res))))

(defrpc calculate-from-remote-data
        [run-id crop-id data & {:keys [user-id pwd]}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))

              ;_ (println "crop-id: " crop-id " climate-data: ")
              ;_ (pp/pprint climate-data)
              ]
          (when cred
            (calculate-from-remote-data* db run-id crop-id data))))

(defrpc set-climate-data-import-time
        [hour minute & {:keys [user-id pwd]}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))
              ]
          (when cred
            (import-dwd/set-import-time-settings hour minute))))

(defrpc bulk-import-dwd-data-into-datomic
        [from to & {:keys [user-id pwd]}]
        {:rpc/pre [(nil? user-id)
                   (rules/logged-in?)]}
        (let [db (db/current-db)

              cred (if user-id
                     (db/credentials* db user-id pwd)
                     (:user @*session*))
              ]
          #_(println "from: " (pr-str (ctf/parse (ctf/formatters :date) from))
                   " to: " (pr-str (ctf/parse (ctf/formatters :date) to)))
          (when cred
            (import-dwd/bulk-import-dwd-data-into-datomic (ctf/parse (ctf/formatters :date) from)
                                                          (ctf/parse (ctf/formatters :date) to)))))














