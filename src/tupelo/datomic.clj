(ns tupelo.datomic
  (:use tupelo.core)
  (:refer-clojure :exclude [update partition])
  (:require
    [datomic.api :as d]
    [schema.core :as s]
    [tupelo.datomic.schema :as tdsk] ; #todo tsd -> tds
    [tupelo.schema :as tsk]
    [clojure.string :as str]))

;---------------------------------------------------------------------------------------------------
; Notes:
;
; EAVT makes Datomic a database of *facts*.  EAV is just a database of *state*.
; Relation:   A set of maps (possibly shortened to a vector)
; Tuple:      A (fixed-length) vector (usually one of a group)
; Value:      A primitive value like "Joe" or 42
;
;---------------------------------------------------------------------------------------------------
; #todo
; - Verify that on update, retraction of old & assertion of new both get same tx/timestamp
; - Each entity should have an :entity/type attr, populated by ident-vals like :entity.type/person,
;   :entity.type/address, etc.
; - Each entity.type should have an entity.type.*/invariants list of functions which must always be
;   true (integrity constraints).
; - Add "enum" like keyword attrs (not entities)
; - Look at seek-datoms & entid-at (Craig Andera StrangeLoop talk 2013)
;
; So a an Entity of type :entity.type/person looks like:
;              <name>          <type>      <constraints/invariants>
;             :person/name     String    #{ <english alphabet> fn.2 ... }
;             :person/email    String    #{ <email constraints> fn.2 ... }
;             :person/phone    long      #{ <us=10 digits> fn.2 ... }
;             :entity/type     attr      :entity.type/person
; Does then [?eid :entity/_type  :entity.type/person] yield a list of all "person" entities?
;
;---------------------------------------------------------------------------------------------------
; Note that issuing a query is a bit like defining a function that is then immediately compiled and
; used inline (kind of like SQL query strings).  Example
;
;     (td/query-entity :let    [$    (live-db)     ; assign multiple query variables
;                               ?loc "Caribbean"]  ; just like clojure 'let' special form
;                      :find   [?eid ?name] ; <- output tuple shape
;                      :where  [ [?eid :person/name ?name      ]
;                                [?eid :location    ?loc] ] )
; maybe reformat to:
;
;     (td/query-entity :let     [$     (live-db)
;                                ?loc  "Caribbean" ]
;                      :return  [?eid ?name]
;                      :where   { :person/name ?name  :location ?loc } )  ; attr/val [:eid ?eid] is implied
; or
;     (td/query-entity :let     [$     (live-db)
;                                ?loc  "Caribbean" ]
;                      :return  [?my-eid ?name]
;                      :where   { :eid ?my-eid  :person/name ?name  }  ; if always last, can just list 1+ maps
;                               { :eid ?my-eid  :location    ?loc   :weapon #{ :gun } } )
;                               ; maps proc'd in order (allows explicit search order when needed)
;                               ; explicit [:eid ?my-eid] attr/val overrides default implied entry
;
;---------------------------------------------------------------------------------------------------

; Prismatic Schema type definitions
(s/set-fn-validation! true)   ; #todo add to Schema docs

(def reserved-attrvals
 "A map that defines the set of permissible values for use in attribute definition.

  User-defined attributes are special entities in Datomic. They are stored in the :db.part/db
  partition, and are defined by special attributes that are built-in to Datomic (this is analgous to
  the special forms that are built-in to Clojure). The root attributes are named by the following
  keywords (all in the 'db' namespace):

    :db/id
    :db/ident
    :db/valueType
    :db/cardinality
    :db/unique
    :db/doc
    :db/index
    :db/fulltext
    :db/isComponent
    :db/noHistory

  For each of these special attributes, this map defines the permissible values used for specifying
  user-defined attributes. Most special attributes are defined by a set of permissible keyword
  values. Permissible values for other special attributes are defined by a predicate function.  "
  { :db/valueType
      #{ :db.type/keyword   :db.type/string   :db.type/boolean  :db.type/long     :db.type/bigint
         :db.type/float     :db.type/double   :db.type/bigdec   :db.type/bytes
         :db.type/instant   :db.type/uuid     :db.type/uri      :db.type/ref }

    :db/cardinality   #{ :db.cardinality/one :db.cardinality/many }

    :db/unique        #{ :db.unique/value :db.unique/identity }

  ; #todo - document & enforce types & values for these attrs:
  ;   :db/ident #(keyword? %)
  ;   :db/doc #(string? %)
  ;   :db/index #{ true false }
  ;   :db/fulltext #{ true false }
  ;   :db/isComponent #{ true false }
  ;   :db/noHistory #{ true false }
  } )

;---------------------------------------------------------------------------------------------------
; Core functions

(s/defn new-partition :- tsk/KeyMap
 "Returns the tx-data to create a new partition in the DB. Usage:

  (td/transact *conn*
    (partition ident)) 
  "
  [ident :- s/Keyword]
; (when-not (keyword? ident)
;   (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  { :db/id                    (d/tempid :db.part/db) ; The partition :db.part/db is built-in to Datomic
    :db.install/_partition    :db.part/db   ; Ceremony so Datomic "installs" our new partition
    :db/ident                 ident } )     ; The "name" of our new partition

(s/defn new-attribute    :- tsk/KeyMap
 "Returns the tx-data to create a new attribute in the DB.  Usage:

    (td/transact *conn*
      (attribute ident value-type & options))

  The first 2 params are required. Other params are optional and will use normal Datomic default
  values (false or nil) if omitted. An attribute is assumed to be :db.cardinality/one unless
  otherwise specified.  Optional values are:

      :db.unique/value
      :db.unique/identity
      :db.cardinality/one     <- assumed by default
      :db.cardinality/many
      :db/index               <- assumed true by default
      :db/fulltext
      :db/isComponent
      :db/noHistory
      :db/doc                 <- *** currently unimplemented ***
  "
  [ ident       :- s/Keyword
    value-type  :- s/Keyword
   & options ]  ; #todo type spec?
; (when-not (keyword? ident)
;   (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  (let [legal-attrvals-set (grab :db/valueType reserved-attrvals) ]
    (when-not (contains? legal-attrvals-set value-type)
      (throw (IllegalArgumentException. (str "attribute value-type invalid: " ident )))))
  (let [base-specs    { :db/id                  (d/tempid :db.part/db)
                        :db.install/_attribute  :db.part/db  ; Datomic ceremony to "install" the new attribute
                        :db/cardinality         :db.cardinality/one   ; default value for most attrs
                        :db/index               true                  ; default for max speed
                        :db/ident               ident
                        :db/valueType           value-type }
        option-specs  (apply glue {}
                        (for [it options]
                          (cond
                            (= it :db.unique/value)         {:db/unique :db.unique/value}
                            (= it :db.unique/identity)      {:db/unique :db.unique/identity}
                            (= it :db.cardinality/one)      {:db/cardinality :db.cardinality/one}
                            (= it :db.cardinality/many)     {:db/cardinality :db.cardinality/many}
                            (= it :db/index)                {:db/index true}
                            (= it :db/noindex)              {:db/index false}
                            (= it :db/fulltext)             {:db/fulltext true}
                            (= it :db/isComponent)          {:db/isComponent true}
                            (= it :db/noHistory)            {:db/noHistory true}
                            (string? it)                    {:db/doc it}))) ; #todo finish this
        tx-specs      (glue base-specs option-specs)
  ]
    tx-specs))

; #todo need test
(s/defn new-entity  :- tsk/KeyMap
 "Returns the tx-data to create a new entity in the DB. Usage:

    (td/transact *conn*
      (new-entity           attr-val-map)   ; default partition -> :db.part/user
      (new-entity partition attr-val-map))  ; user-specified partition

  where attr-val-map is a Clojure map containing attribute-value pairs to be added to the new
  entity."
  ( [ attr-val-map    :- tsk/KeyMap ]
   (new-entity :db.part/user attr-val-map))
  ( [ -partition      :- s/Keyword
      attr-val-map    :- tsk/KeyMap ]
    (glue {:db/id (d/tempid -partition) } attr-val-map)))

; #todo pair create-enum (rename) & create-or-verify-enum (idempotent)
;         declarative: describes desired end state, not how to get there
; #todo need test
(s/defn new-enum :- tsk/KeyMap   ; #todo add namespace version
 "Returns the tx-data to create a new enumeration entity in the DB. Usage:

    (td/transact *conn*
      (new-enum ident))

  where ident is the (keyword) name for the new enumeration entity."
  [ident :- s/Keyword]
; (when-not (keyword? ident)
;   (throw (IllegalArgumentException. (str "attribute ident must be keyword: " ident ))))
  (new-entity {:db/ident ident} ))

; #todo  -  document entity-spec as EID or refspec in all doc-strings
; #todo  -  document use of "ident" in all doc-strings (EntityIdent?)
(s/defn update :- tsk/KeyMap
 "Returns the tx-data to update an existing entity. Usage:

    (td/transact *conn*
      (update entity-spec attr-val-map))

   where attr-val-map is a Clojure map containing attribute-value pairs to be added to the new
   entity.  For attributes with :db.cardinality/one, Datomic will (automatically) retract the
   previous value prior to the insertion of the new value. For attributes with :db.cardinality/many,
   the new value will be accumulated into the current set of values."
  [entity-spec    :- tdsk/EntitySpec
   attr-val-map   :- tsk/KeyMap ]
    (glue {:db/id entity-spec} attr-val-map))
; #todo error check: for each attr, if it is :card/many, verify value is a set!

(s/defn retract-value :- tsk/Quad
  "Returns the tx-data to retract an attribute-value pair for an entity. Only a single
   attribute-value pair can be retracted for each call to retract-value.  Usage:

    (td/transact *conn*
      (retract-value entity-spec attribute value))

   where the attribute-value pair must exist for the entity or the retraction will fail.  " ; #todo verify
  [entity-spec  :- tdsk/EntitySpec
   attribute    :- s/Keyword
   value        :- s/Any ]
  [:db/retract entity-spec attribute value] )

(s/defn retract-entity :- tsk/Pair
 "Returns the tx-data to retract all attribute-value pairs for an entity, as well as all references
  to the entity by other entities. Usage:

    (td/transact *conn*
      (retract-entity entity-spec))

  If the retracted entity refers to any other entity through an attribute with :db/isComponent=true,
  the referenced entity will be recursively retracted as well."
  [entity-spec  :- tdsk/EntitySpec ]
  [:db.fn/retractEntity entity-spec] )

; #todo need test
(s/defn transact :- s/Any  ; #todo
 "Like (datomic.api/transact ...) but does not require wrapping everything in a Clojure vector. Usage:

    (td/transact *conn*
      (td/new-entity attr-val-map)                 ; default partition -> :db.part/user
      (td/update entity-spec-1 attr-val-map-1)
      (td/update entity-spec-2 attr-val-map-2))
  "
  [conn & tx-specs]
  (d/transact conn tx-specs))

;---------------------------------------------------------------------------------------------------

(defn query-sym->kw
  [qs]
  (it-> qs
    (sym->str it)
    (apply str (drop 1 it))
    (str->kw it)))

(defn partition-even-odd ; #todo rename => uninterleave ??? (un- or demultiplex ???)
  "Separates elements of a vector by index into even & odd values.  Not lazy.
  [:a :b :c :d] => [ [:a :c] [:b :d] ] "
  [seq-arg]
  (let [evens (mapv only (clojure.core/partition 1 2 seq-arg))
        odds  (mapv only (clojure.core/partition 1 2 (drop 1 seq-arg)))]
    [evens odds]))

(s/defn query-sym? :- s/Bool
  "Returns true iff a symbol is a legal Datomic query symbol (beginning with a `?` char)"
  [arg :- s/Any]
  (and (symbol? arg)
    (= \? (first (name arg)))))

(s/defn query-sym-wild? :- s/Bool
  "Returns true iff a symbol is a legal Datomic query symbol (beginning with a `?` char),
  and is a wildcard symbol (ending in a `*` char)"
  [arg :- s/Any]
  (and (query-sym? arg)
    (= \* (last (name arg)))))

(s/defn check-symbol-usage
  "Throws an exception if:
     - a wildcard query symbol is appears more than once
     - any other symbol appears only once
   Else returns `:ok` "
  [syms-vec :- tsk/Vec]
  (let [syms-query-wild    (keep-if query-sym-wild? syms-vec)
        syms-reg           (drop-if query-sym-wild? syms-vec)
        freq-map-reg       (frequencies syms-reg)
        freq-map-wild      (frequencies syms-query-wild)
        orphan-syms        (vec (keys (submap-by-vals freq-map-reg [1] :missing-ok)))
        val-over-1?        (fn [k v] (when (< 1 v)
                                       [k v]))
        overused-wildcards (vec (keys (keep-if val-over-1? freq-map-wild))) ]
    (when (not-empty? orphan-syms)
      (throw (ex-info "Orphan symbols found:" (vals->map orphan-syms))))
    (when (not-empty? overused-wildcards)
      (throw (ex-info "Overused wildcards found:" (vals->map overused-wildcards))))
    :ok))

(s/defn ^:no-doc where-clause :- tsk/TupleList
  "Process the `:where` clause in the find-base macro"
  [maps :- tsk/MapList]
  (apply glue
    (forv [curr-map maps]
      (let [eid-vec      [(grab :db/id curr-map)]
            attrvals-map (dissoc curr-map :db/id)
            inner-result (forv [entry attrvals-map]
                           (glue eid-vec entry))]
        inner-result))))

(s/defn ^:no-doc query-impl
  [ctx :- tsk/KeyMap]
  (let
    [where-vec            (where-clause (grab :where ctx))                ; >> (spyx-pretty where-vec)
     preds-vec            (get ctx :preds [])                             ; >> (spyx-pretty preds-vec)
     preds2-vec           (mapv vector preds-vec)                         ; >> (spyx-pretty preds2-vec)
     rules-vec            (get ctx :rules [])                             ; >> (spyx-pretty rules-vec)
     let-vec              (grab :let ctx)                                 ; >> (spyx let-vec)
     [let-syms let-srcs]  (partition-even-odd let-vec)                    ; >> (spyx [let-syms let-srcs] )
     yield-vec            (grab :yield ctx)                               ; >> (spyx yield-vec)
     yield-kws            (mapv  query-sym->kw  yield-vec)                ; >> (spyx yield-kws)
     where-vec-final      (glue where-vec preds2-vec rules-vec)           ; >> (spyx where-vec-final)
     query-syms           (keep-if query-sym? (flatten [where-vec let-syms yield-vec rules-vec])) ]
    (check-symbol-usage query-syms)
    ; Look for orphaned symbols
    `(let [query-tuples# (d/q '{:find  ~yield-vec
                                :in    [~@let-syms]
                                :where ~where-vec-final }
                           ~@let-srcs)
           result-set#   (set (map vec query-tuples#) )]
       result-set#)))

(defmacro query
  "Returns search results as a set of maps (i.e. a TupleSet, or #{ [s/Any] } in Prismatic Schema),
   where each tuple is unique. Usage:

    (td/query-vec {
       :let    [$        (d/db *conn*)     ; assign multiple variables just like
                ?name    \"Caribbean\"]    ;   in Clojure 'let' special form
       :preds  [ (< 1960 ?year) (< ?year 1970) ]
       :yield  [?e ?name]
       :where  {:db/id ?eid  :person/name ?name  :location ?loc}
               {:db/id ?eid  :weapon/type :weapon/wit}  })

  Unlike datomic.api/q, the query form does not need to be wrapped in a map literal nor is any
  quoting required. Most importantly, the :in keyword has been replaced with the :let keyword, and
  the syntax has been copied from the Clojure let special form so that both the query variables (the
  variables $ and ?name in this case) are more closely aligned with their actual values. Also, the
  implicit DB $ must be explicitly tied to its data source in all cases (as shown above).
  The `:let` and `:yield` clauses may be in any order, but the `:where` clause must come last.

  An exception is thrown if an orphan query symbol is found (eg `?nammmme`), where 'orphan' means 'used once'. Intentional
  wildcards (free symbols) must end in a `*` character like '?dont-care*'
   "
  [ctx]
  (query-impl ctx))

(s/defn ^:no-doc query-map-impl
  [ctx :- tsk/KeyMap]
  (let
    [where-vec            (where-clause (grab :where ctx))                ; >> (spyx-pretty where-vec)
     preds-vec            (get ctx :preds [])                             ; >> (spyx-pretty preds-vec)
     preds2-vec           (mapv vector preds-vec)                         ; >> (spyx-pretty preds2-vec)
     rules-vec            (get ctx :rules [])                             ; >> (spyx-pretty rules-vec)
     let-vec              (grab :let ctx)                                 ; >> (spyx let-vec)
     [let-syms let-srcs]  (partition-even-odd let-vec)                    ; >> (spyx [let-syms let-srcs] )
     yield-vec            (grab :yield ctx)                               ; >> (spyx yield-vec)
     yield-kws            (mapv  query-sym->kw  yield-vec)                ; >> (spyx yield-kws)
     where-vec-final      (glue where-vec preds2-vec rules-vec)           ; >> (spyx where-vec-final)
     query-syms           (keep-if query-sym? (flatten [where-vec let-syms yield-vec rules-vec]))
     ]
    (check-symbol-usage query-syms)
    ; Look for orphaned symbols
    `(let [query-tuples# (d/q '{:find  ~yield-vec
                                :in    [~@let-syms]
                                :where ~where-vec-final }
                           ~@let-srcs)
           result-set#   (set (for [tuple# query-tuples#]
                                (zipmap ~yield-kws (vec tuple#))))]
       result-set#)))

(defmacro query-map
  "Returns search results as a set of maps (i.e. a TupleSet, or #{ [s/Any] } in Prismatic Schema),
   where each tuple is unique. Usage:

    (td/query
       :let    [$        (d/db *conn*)     ; assign multiple variables just like
                ?name    \"Caribbean\"]    ;   in Clojure 'let' special form
       :preds  [ (< 1960 ?year) (< ?year 1970) ]
       :yield  [?e ?name]
       :where  {:db/id ?eid  :person/name ?name  :location ?loc}
               {:db/id ?eid  :weapon/type :weapon/wit} )

  Unlike datomic.api/q, the query form does not need to be wrapped in a map literal nor is any
  quoting required. Most importantly, the :in keyword has been replaced with the :let keyword, and
  the syntax has been copied from the Clojure let special form so that both the query variables (the
  variables $ and ?name in this case) are more closely aligned with their actual values. Also, the
  implicit DB $ must be explicitly tied to its data source in all cases (as shown above).
  The `:let` and `:yield` clauses may be in any order, but the `:where` clause must come last.

  Each map in result set is keyword labeled such that:

      (query-map
        ...
        :yield [?gid ?ident-type ?ident-gender]  ; Datomic query symbols like `?some-symbol`
        ...)

  produces output like:

      #{...    ; maps keyed by keyword version `:some-symbol` with `?` stripped => `:`
          {:gid           #uuid '76c9a186-75bd-436a-85c0-823e3efddb7f'
           :ident-type    :artist.type/person
           :ident-gender  :artist.gender/female}
        ...)

  An exception is thrown if an orphan query symbol is found (eg `?nammmme`), where 'orphan' means 'used once'. Intentional
  wildcards (free symbols) must end in a `*` character like '?dont-care*'
   "
  [ctx]
  (query-map-impl ctx))

(defn- ^:no-doc contains-pull?  ; prevent codox ("lein doc") from processing
 "Returns true if a sequence of symbols includes 'pull'"
  [args-vec]
  (let [args-map    (apply hash-map args-vec)
        find-vec    (flatten [ (grab :yield args-map) ] ) ]
    (has-some? #(= 'pull %) find-vec)))

;---------------------------------------------------------------------------------------------------
; #todo need checks to stop collection result (:find [?e ...])
; #todo and scalar result (:find [?e .])
(defmacro ^:no-doc query-base    ; #todo remember 'with'
  ; returns a HashSet of datomic entity objects
  "Base macro for improved API syntax for datomic.api/q query function (Entity API)"
  [& args]
  ; (newline) (println "find-base =>" args)
  (when-not (= :where (nth args 4))
    (throw (IllegalArgumentException.
             (str "find-base: 5th arg must be :where, received=" args))))
  (let
    [let-find-map  (apply hash-map (take 4 args))                           ; >> (spyx let-find-map)
     where-entries (where-clause (drop 5 args))                             ; >> (spyx where-entries)
     args-map      (glue let-find-map {:where where-entries})               ; >> (spyx args-map)
     let-vec       (grab :let args-map)                                     ; >> (spyx let-vec)
     let-map       (apply hash-map let-vec)                                 ; >> (spyx let-map)
     let-syms      (keys let-map)                                           ; >> (spyx let-syms)
     let-srcs      (vals let-map)                                           ; >> (spyx let-srcs)
     yield-vec    (grab :yield args-map)                                    ; >> (spyx yield-vec)
     where-vec     (grab :where args-map)                                   ; >> (spyx where-vec)
     ]
    (flush)
    (when-not (vector? let-vec)
      (throw (IllegalArgumentException. (str "find-base: value for :let must be a vector; received=" let-vec))))
    (when-not (vector? yield-vec)
      (throw (IllegalArgumentException. (str "find-base: value for :yield must be a vector; received=" yield-vec))))
    (when-not (vector? where-vec)
      (throw (IllegalArgumentException. (str "find-base: value for :where must be a vector; received=" where-vec))))
    `(d/q  '{:find   ~yield-vec
             :where  ~where-vec
             :in     [ ~@let-syms ] }
       ~@let-srcs)))

(comment
  ; #todo change :find -> :return  ?
  (defmacro query
    "Returns search results as a set of tuples (i.e. a TupleSet, or #{ [s/Any] } in Prismatic Schema),
     where each tuple is unique. Usage:

      (td/query
         :let    [$        (d/db *conn*)     ; assign multiple variables just like
                  ?name    \"Caribbean\"]    ;   in Clojure 'let' special form
         :yield  [?e ?name]
         :where  {:db/id ?eid  :person/name ?name  :location ?loc}
                 {:db/id ?eid  :weapon/type :weapon/wit} )

    Unlike datomic.api/q, the query form does not need to be wrapped in a map literal nor is any
    quoting required. Most importantly, the :in keyword has been replaced with the :let keyword, and
    the syntax has been copied from the Clojure let special form so that both the query variables (the
    variables $ and ?name in this case) are more closely aligned with their actual values. Also, the
    implicit DB $ must be explicitly tied to its data source in all cases (as shown above).
    The `:let` and `:yield` clauses may be in any order, but the `:where` clause must come last.
    "
    [& args]
    `(set (for [tuple# (query-base ~@args) ]
            (vec tuple#))))
  )

(defmacro query-pull ; #todo cleanup like query-map.  Re-evaluate API
  "Returns a TupleList [Tuple] of query results, where items may be duplicated. Intended only for
   use with the Datomic Pull API. Usage:

     (td/find-pull   :let    [$ (d/db *conn*) ]
                     :yield [ (pull ?eid [:location]) ]
                     :where  { :db/td ?eid :location ?loc } )

   It is an error if the :yield clause does not contain a Datomic Pull API request.  "
  [& args]
  (when-not (contains-pull? args)
    (throw (IllegalArgumentException.
             (str "query-pull: Only intended for queries using the Datomic Pull API"))))
  `(forv [tuple# (query-base ~@args) ]
     (vec tuple#)))

; #todo: convert to t-find
; #todo: write blog post/forum letter about this testing technique
(defn t-query
  "Test the query macro, returns true on success."
  []
  (let [expanded-result (macroexpand-1 '(tupelo.datomic/query-base
                                          :let [a (src 1)
                                                b val-2]
                                          :yield [?e]
                                          :where {:db/id ?e :person/name ?name} ))]
    (= expanded-result
      '(datomic.api/q (quote {:find  [?e]
                              :in    [a b]
                              :where [[?e :person/name ?name]]})
         (src 1)
         val-2))))

;------------------------------------------------------------------------------------------------------------------
; Informational functions

; #todo: make default?
(s/defn entity-map-full :- tsk/KeyMap  ; #todo - need test & -> demo/doc
  "Returns a map of an entity's attribute-value pairs. A simpler, eager version of datomic/entity."
  [db-val         :- datomic.db.Db
   entity-spec    :- tdsk/EntitySpec ]
  (let [ entity   (d/entity db-val entity-spec)   ; does not include :db/id attr/val
         eid      (:db/id entity) ]               ; ... unless we explicitly ask for it
    (into {:db/id eid} entity)))

(s/defn entity-map :- tsk/KeyMap  ; #todo - need test
  "Returns a map of an entity's attribute-value pairs. A simpler, eager version of datomic/entity."
  [db-val         :- datomic.db.Db
   entity-spec    :- tdsk/EntitySpec ]
  (into {} (d/entity db-val entity-spec)))

; #todo - need test
(s/defn eid->ident :- s/Keyword
  "Returns the keyword ident value given an EID value"
  [db-val     :- datomic.db.Db
   eid-val    :- tdsk/Eid]
  (d/q '{:find  [?ident .]
         :in    [$ ?eid]
         :where [ [?eid :db/ident ?ident] ] }
       db-val eid-val ))

; #todo write a blog post documenting keywords [:e :a :v :tx :added] for #datom[0 10 :db.part/db 13194139533312 true]
; (pr t1) => #datom[299067162756085 63 "Honey Rider" 13194139534324 true]
; #todo - need test
(s/defn datom-map :- tdsk/DatomMap
 "Returns a plain Clojure map of an datom's attribute-value pairs.
  A datom map is structured as:

    { :e      - entity id (eid)
      :a      - attribute eid
      :v      - value
      :tx     - transaction eid
      :added  - true/false (assertion/retraction) }
  "
  [datom :- s/Any]  ; #todo
  {:e     (:e datom)
   :a     (long (:a datom)) ; must cast Integer -> Long
   :v     (:v datom) ; #todo - add tests to catch changes
   :tx    (:tx datom)
   :added (:added datom)})

; #todo - need test
; #todo - make non-lazy?
(s/defn datoms :- [tdsk/DatomMap]
 "Returns a lazy sequence of Clojure maps of an datom's attribute-value pairs.
  A datom map is structured as:

    { :e      - entity id (eid)
      :a      - attribute eid
      :v      - value
      :tx     - transaction eid
      :added  - true/false (assertion/retraction) }

  Like (datomic.api/datoms ...), but returns a seq of plain Clojure maps.  "
  [db-val   :- datomic.db.Db
   index    :- s/Keyword
   & components ]  ; #todo
  (for [datom (apply d/datoms db-val index components) ]
    (datom-map datom)))

; #todo - README
(s/defn tx-datoms :- s/Any
  "Returns a vector of datom-maps from a TxResult"
  [db-val     :- datomic.db.Db
   tx-result  :- tdsk/TxResult ]
  (let [tx-data     (:tx-data tx-result)  ; a seq of datoms
        fn-datom    (fn [arg]  
                      "Replace attr-eid -> attr-ident in datom"
                      (let [datom1      (datom-map arg)
                            attr-eid    (:a datom1)
                            attr-ident  (eid->ident db-val attr-eid)
                            datom2      (assoc datom1 :a attr-ident) ]
                        datom2 ))
        tx-datoms    (mapv fn-datom tx-data)
    ]
      tx-datoms))
; #todo:  map :v using eid->ident if attribute (:a) is a "enum ref" like :weapon/type
;    { :e 299067162756089,
;      :a :weapon/type,
;      :v 17592186045419,
;      :tx 13194139534328,
;      :added true }

; #todo - need test
(s/defn partition-name :- s/Keyword
  "Returns the partition name (the :db/ident value) for an Entity"
  [db-val       :- datomic.db.Db
   entity-spec  :- tdsk/EntitySpec ]
  (d/ident db-val (d/part entity-spec)))

; #todo add example from bond to README
(s/defn partition-eids  :- [tdsk/Eid]
  "Returns a lazy sequence of all the EIDs in a partition."
  [db-val     :- datomic.db.Db
   part-kw    :- s/Keyword ]
  (let [time-zero     0
        eid-start     (d/entid-at db-val part-kw time-zero)     ; 1st possible eid
        datoms        (d/seek-datoms db-val :eavt eid-start)    ; all datoms >= eid-start
        eids-all      (distinct (map #(:e %) datoms))           ; pull out unique eids
        eids-keep     (take-while #(= part-kw (partition-name db-val %))  ; keep only eids in desired partition
                                  eids-all) ]
    eids-keep))

; #todo from Craig Andera video (InfoQ 2013-12-12) of StrangLoop talk.
#_(defn datoms-between
    "Returns a reducible collection of datoms created between the start and end dates (half-open
     interval) in a single partition."
    [db partition start end]
    (let [start-eid     (d/entid-at db partition start)
          end-eid       (d/entid-at db partition end) ]
      (->> (d/seek-datoms db :eavt start-eid)
           (r/take-while #(< (:e %) end-eid)))))

; #todo - need test
(s/defn is-transaction? :- s/Bool
  "Returns true if an entity is a transaction (i.e. it is in the :db.part/tx partition)"
  [db-val       :- datomic.db.Db
   entity-spec  :- tdsk/EntitySpec ]
  (= :db.part/tx (partition-name db-val entity-spec)))

; #todo - need test
(s/defn transactions :- [ tsk/KeyMap ]
  "Returns a lazy sequence of entity-maps for all DB transactions"
  [db-val :- datomic.db.Db]
  (let [; All transaction entities must have attr :db/txInstant
        candidate-eids    (map :e (datoms db-val :aevt :db/txInstant))
        ; filter in case any user entities have attr :db/txInstant
        tx-eids           (keep-if #(is-transaction? db-val %) candidate-eids)
        result            (map #(entity-map db-val %) tx-eids) ] ; convert to entity-maps
    result))

; #todo need test
(s/defn eids :- [tdsk/Eid]
  "Returns a collection of the EIDs created in a transaction."
  [tx-result :- tdsk/TxResult]
  (vals (grab :tempids tx-result)))

(s/defn txid  :- tdsk/Eid
  "Returns the EID of a transaction"
  [tx-result :- tdsk/TxResult]
  (let [datoms  (grab :tx-data tx-result)
        txids   (mapv :tx datoms) ]
    (assert (apply = txids))  ; all datoms in tx have same txid
    (first txids)))           ; return the first one

;---------------------------------------------------------------------------------------------------
; #todo: make helper fn's for rule creation
; (def-rule <name> [args]
;   [?com-eid    :community/neighborhood   ?nbr]          ; rule clause
;   [?nbr        :neighborhood/district    ?dist]         ; rule clause
;   [?dist       :district/region          ?reg]          ; rule clause
;   [?reg        :db/ident                 ?reg-ident] ]  ; rule clause
;
; literal way:
; (let[ rules-list   '[ [ (com-region ?com-eid ?reg-ident) ; rule header
;                         [?com-eid    :community/neighborhood   ?nbr]          ; rule clause
;                         [?nbr        :neighborhood/district    ?dist]         ; rule clause
;                         [?dist       :district/region          ?reg]          ; rule clause
;                         [?reg        :db/ident                 ?reg-ident] ]  ; rule clause
;                     ]
;---------------------------------------------------------------------------------------------------
; #todo: make helper fn's for enum invarient enforcement (no random entities assigned)
;---------------------------------------------------------------------------------------------------
; Pull stuff
; #todo:  pull-one
; #todo:  pull-many
; #todo:  pull-deep (pull-recursive) ; need a limit?

; #todo: Blog idea: name the indexes with english names
;   :eavt   entity index (row-index)
;   :aevt   attr   index (col-index)
;   :avet   value  index (sorted-col)
;   :vaet   reverse index (reverse entity index)
;
;   question:  should we index every attr by default?

; #todo make easy to have attr namespace be like table name.
;   table     persons           <- table    <-> set of person entities (rows)
;   entity:   person            <- entity   <-> table row
;   attrs:    person/name       <- attr     <-> table column
;             person/address
;             person/phone
;   Need validator fns to ensure/verify person entity has all person/* attrs an no other attrs.
;     need to add type field {:db/entity-type :person}
;   Need map from :db/entity-type to validation-fn
;     { :person  (fn [eid] (every?  #{:person/name :person/address :person/phone}
;                                   #(keys (entity-map eid)) ))
;     }

