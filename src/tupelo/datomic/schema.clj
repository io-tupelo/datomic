(ns tupelo.datomic.schema
  (:use tupelo.core)
  (:require
    [schema.core      :as s]
    [tupelo.schema    :as ts]
  ))

(def Eid
  "Each entity in the DB is uniquely specified its Entity ID (EID).  Indeed, allocation of a unique
   EID is what 'creates' an entity in the DB."
  Long)

; #todo - clarify in all doc-strings that entity-spec = [EID or lookup-ref]
(def LookupRef
  "If an entity has an attribute with either :db.unique/value or :db.unique/identity, that entity
   can be uniquely specified using a lookup-ref (LookupRef). A lookup-ref is an attribute-value pair
   expressed as a tuple:  [ <attribute> <value> ]"
  [(s/one s/Keyword "attr")
   (s/one s/Any "val")])

(def EntitySpec
  "An EntitySpec is used to uniquely specify an entity in the DB. It consists of
   either an EID or a LookupRef."
  (s/if int? Eid LookupRef))

(def DatomMap
  "The Clojure map representation of a Datom."
  {:e Eid :a Eid :v s/Any :tx Eid :added s/Bool})

(def TxResult
  "A map returned by a successful transaction. Contains the keys 
   :db-before, :db-after, :tx-data, and :tempids"
  { :db-before    datomic.db.Db
    :db-after     datomic.db.Db
    :tx-data      [s/Any]  ; #todo (seq of datom)
    :tempids      ts/Map } )  ; #todo

