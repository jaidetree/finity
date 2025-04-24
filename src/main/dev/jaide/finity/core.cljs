(ns dev.jaide.finity.core
  (:refer-clojure :exclude [])
  (:require
   [clojure.core :as cc]
   [clojure.string :as s]
   [cljs.core :refer [IDeref ILookup]]
   [dev.jaide.valhalla.core :as v]
   [dev.jaide.finity.validators :refer [define-validator]]
   [clojure.pprint :refer [pprint]]))

(defn create
  "Create an fsm-spec atom
  Arguments:
  - id - Keyword used to identify the FSM-spec
  - opts - Optional options hash-map

  Options:
  - :atom - A function to instantiate an atom. For example reagent.core/ratom

  Returns an atom containing an empty fsm-spec hash-map"
  [id & {:keys [atom] :as opts
         :or {atom atom}}]
  (atom
   {:fsm/id      id
    :initial     {:state nil
                  :context {}}
    :transitions {}
    :cleanup-effect nil
    :effects     {}
    :validators  {:states {}
                  :actions {}
                  :effects {}}
    :opts        opts}))

(defn fsm?
  "Determines if a possible fsm-spec-atom is likely an fsm-spec

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function

  Returns true if the argument is an atom with the :fsm/id property"
  [fsm-spec-ref]
  (contains? @fsm-spec-ref :fsm/id))

(defn assert-fsm-spec
  "Asserts that the argument is an fsm-spec-ref atom or throws an error

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function

  Returns nil
  "
  [fsm-spec-ref]
  (assert (fsm? fsm-spec-ref) "Invalid FSM, missing :fsm id"))

(defn state
  "Defines a valid state and context validator

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - id - Keyword identifying the unique state for example :idle
  - context-validator-map - An optional hash-map of context keywords to
                            valhalla compatible validation functions

  Returns the fsm-spec-ref atom with a state validator defined
  "
  [fsm-spec-ref id & [context-validator-map]]
  (assert-fsm-spec fsm-spec-ref)
  (assert (keyword? id) "State id value is required and must be a keyword")
  (assert (or (nil? context-validator-map)
              (map? context-validator-map) "Context is an optional hash-map"))
  (when (fn? (get-in @fsm-spec-ref [:validators :states id]))
    (throw (js/Error. (str "State already defined " (pr-str id)))))
  (let [context-validator (if (nil? context-validator-map)
                            (v/literal {})
                            (v/record context-validator-map))
        validator (v/record {:state (v/literal id)
                             :context context-validator
                             :effect (v/nilable (v/assert map?))})]
    (swap! fsm-spec-ref assoc-in [:validators :states id] validator)
    fsm-spec-ref))

(defn- action-validator
  [id v-map]
  (v/record (-> {:type (v/literal id)}
                (merge (when (map? v-map)
                         v-map)))))

(defn action
  "Defines a valid action and validator

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - id - Keyword identifying the unique action for example :fetch
  - validator-hash-map - An optional hash-map of action keywords to
                         valhalla compatible validation functions

  Returns the fsm-spec-ref atom with an action validator defined"
  [fsm-spec-ref id & [validator-map]]
  (assert-fsm-spec fsm-spec-ref)
  (assert (keyword? id) "Action id must be a keyword")
  (assert (or (map? validator-map)
              (nil? validator-map)) "Validator map must be nil or a hash-map of keys to validators")
  (when (fn? (get-in @fsm-spec-ref [:validators :actions id]))
    (throw (js/Error. (str "Already defined action for " (pr-str id)))))
  (swap! fsm-spec-ref assoc-in [:validators :actions id] (action-validator id validator-map))
  fsm-spec-ref)

(defn- effect-validator
  [id v-map]
  (v/record (-> {:id (v/literal id)}
                (merge (when (map? v-map)
                         v-map)))))

(defn effect
  "Defines a valid effect and validator

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - id - Keyword identifying the unique effect for example :start-timer
  - validator-hash-map - An optional hash-map of effect argument keywords to
                         valhalla compatible validation functions
  - handler - Required function to receive a hash map with the following:
    - :fsm - Instance of the fsm
    - :state - Current state that was just transitioned to
    - :action - Action that caused the transition
    - :dispatch - Function to dispatch more actions
    - :effect - The effect hash-map with {:id <id>} and possible args

  The handler can optionally return a function to cleanup the side-effect such
  as removing a DOM listener.

  Returns the fsm-spec-ref atom with an effect validator defined"
  ([fsm-spec-ref id handler]
   (effect fsm-spec-ref id nil handler))
  ([fsm-spec-ref id validator-map handler]
   (assert (keyword? id) "Effect id must be a keyword")
   (assert (or (map? validator-map)
               (nil? validator-map))
           "Validator map must be nil or a hash-map of keys to validators")
   (assert (fn? handler) "Effect handler must be a function")
   (when (fn? (get-in @fsm-spec-ref [:validators :effects id]))
     (throw (js/Error. (str "Effect already defined for " (pr-str id)))))
   (swap! fsm-spec-ref assoc-in [:validators :effects id] (effect-validator id validator-map))
   (swap! fsm-spec-ref assoc-in [:effects id] handler)
   fsm-spec-ref))

(defn- transitions-map->kvs
  [fsm states actions]
  (doseq [action actions]
    (when-not (fn? (get-in fsm [:validators :actions action]))
      (throw (js/Error. (str "Could not find validator for action " action)))))
  (doseq [state states]
    (when-not (fn? (get-in fsm [:validators :states state]))
      (throw (js/Error. (str "Could not find validator for state " state)))))
  (for [state states
        action actions]
    [state action]))

(defn transition
  "Define a transition function between states from actions

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - transition-map - A hash-map combining states, actions, and to states
  - f-or-kw - A transition function that receives the current state and the
              action or keyword for simple state value transitions.

  Transition Map:
  - :from - Vector of state keywords to transition from
  - :actions - Vector of keywords representing the :type keyword of actions
  - :to - Vector of possible destination states

  Returns the fsm-spec-ref atom with a transition function defined"
  [fsm-spec-ref {:keys [from actions to]} f-or-kw]
  (assert-fsm-spec fsm-spec-ref)
  (let [fsm @fsm-spec-ref
        transitions (transitions-map->kvs fsm from actions)]
    (doseq [state-action-vec transitions]
      (when (fn? (get-in @fsm-spec-ref [:transitions state-action-vec :reducer]))
        (throw (js/Error. (str "Transition already defined for state "
                               (pr-str (first state-action-vec))
                               " and action " (pr-str (second state-action-vec))))))
      (swap! fsm-spec-ref assoc-in [:transitions state-action-vec]
             {:allowed-states (if (keyword? f-or-kw)
                                #{f-or-kw}
                                (set to))
              :reducer (if (keyword? f-or-kw)
                         (fn []
                           {:state f-or-kw
                            :context {}
                            :effect nil})
                         f-or-kw)}))
    fsm-spec-ref))

(defn parse-state
  "Assert a state matches a defined state and passes validation.
  Mostly intended for internal use or implementing other state adapters.

  Arguments:
  - fsm-spec - An fsm-spec hash-map already derefed
  - state - A hash-map with :state keyword and :context hash-map

  Returns the parsed output of the state validator
  "
  [fsm-spec state]
  (if-let [validator (get-in fsm-spec [:validators :states (:state state)])]
    (v/parse validator state
             :message (fn [{:keys [errors]}]
                        (str "Invalid state\n" (v/errors->string errors))))
    (throw (js/Error. (str "Validator not found for state, got "
                           (pr-str state))))))

(defn parse-effect
  "Assert an effect matches a defined effect and passes validation.
  Mostly intended for internal use or implementing other state adapters.

  Arguments:
  - fsm-spec - An fsm-spec hash-map already derefed
  - effect - A hash-map with :id keyword and arg attrs

  Returns the parsed output of the effect validator
  "
  [fsm-spec effect]
  (when (some? effect)
    (let [effect (if (keyword? effect) {:id effect} effect)]
      (if-let [validator (get-in fsm-spec [:validators :effects (:id effect)])]
        (v/parse validator effect)
        (throw (js/Error. (str "Validator not found for effect, got "
                               (pr-str effect))))))))

(defn initial
  "Set default initial state of fsm spec. Can be overwritten in atom-fsm
  function. Must be called after states are defined as state will be validated.

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - state - A state hashmap with :state and :context attrs or value keyword

  Returns the fsm-spec-ref atom for chaining"
  [fsm-spec-ref state]
  (let [fsm-spec @fsm-spec-ref
        state (if (keyword? state) {:state state} state)
        state (merge {:context {}} state)
        state (parse-state fsm-spec state)]
    (swap! fsm-spec-ref assoc :initial state)
    fsm-spec-ref))

(defn- define-states
  [fsm-spec-ref states]
  (doseq [[id validator-map] states]
    (if (= validator-map {})
      (state fsm-spec-ref id)
      (state fsm-spec-ref id validator-map)))
  fsm-spec-ref)

(defn- define-actions
  [fsm-spec-ref actions]
  (doseq [[id validator-map] actions]
    (if (= validator-map {})
      (action fsm-spec-ref id)
      (action fsm-spec-ref id validator-map)))
  fsm-spec-ref)

(defn- define-effects
  [fsm-spec-ref effects]
  (doseq [[id [validator-map handler]] effects]
    (if (= validator-map {})
      (effect fsm-spec-ref id handler)
      (effect fsm-spec-ref id validator-map handler)))
  fsm-spec-ref)

(defn- define-transitions
  [fsm-spec-ref transitions]
  (doseq [trn transitions]
    (if (keyword? (:to trn))
      (transition fsm-spec-ref (dissoc trn :to) (:to trn))
      (transition fsm-spec-ref (dissoc trn :do) (:do trn))))
  fsm-spec-ref)

(defn define
  "Defines a whole fsm-spec in a single call. Requires all states, actions,
  effects, and transitions but more can be added after.
  
  Arguments:
  - spec - A hash-map describing the state-machine spec
  
  Example:
  (fsm/define
    {:id :traffic
     :initial {:state :green
             :context {}}

     :states {:green {} ;; hash-map is a map of validators
              :red {}
              :yellow {}}
     :actions {:change {}} ;; hash-map is a map of validators
     
     :effects {:wait [{:delay (v/number)}
                      (fn [] ...)}
     :transitions
     [{:from [:red]
       :actions [:change]
       :to [:green] ;; vector requires :do function
       :do (fn [state action]
             {:state :green
              :effect {:id :wait :delay 60_000})}}
      {:from [:green]
       :actions [:change]
       :to :yellow} ;; Does not support effects
      {:from [:yellow]
       :actions [:change]
       :to :red}]}) 

  Returns fsm-spec-ref atom, similar to `(create ...)`
  "
  [spec]
  (let [{spec :output} (v/assert-valid define-validator spec)]
    (-> (create (:id spec))
        (define-states (:states spec))
        (define-actions (:actions spec))
        (define-effects (:effects spec))
        (define-transitions (:transitions spec))
        (initial (:initial spec)))))

(defn init
  "Validate an initial state, context, and effect. Intended for internal use
  or implementing new state adapters.

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - state - A hash-map with :state state keyword and optional :context attr
  - effect - Effect hash-map with :id and arg attrs, needs to match defined
             effect validator

  Returns state hash-map with :state, :context, and :effect keys
  "
  [fsm-spec-ref state & [effect]]
  (assert-fsm-spec fsm-spec-ref)
  (let [fsm-spec  @fsm-spec-ref
        state {:state (:state state)
               :context (:context state)
               :effect (parse-effect fsm-spec effect)}]
    (parse-state fsm-spec state)))

(defn parse-action
  "Validates an action matches a defined action validator

  Arguments:
  - fsm-spec - An fsm-spec hash-map already derefed
  - action - An action hash-map with a :type and arg attrs

  Returns action hash-map"
  [fsm-spec action]
  (let [action (if (keyword? action)
                 {:type action}
                 action)
        validator (get-in fsm-spec [:validators :actions (:type action)])]
    (assert (fn? validator) (str "Action not defined, got " (pr-str action)))
    (v/parse validator action
             :message (fn [{:keys [errors]}]
                        (str "Invalid action:\n" (v/errors->string errors))))))

(defn- get-transition-entry
  [fsm-spec state action]
  (let [entry (get-in fsm-spec [:transitions [(:state state) (:type action)]])
        {:keys [reducer]} entry]
    (when (and (not (fn? reducer)) (get-in fsm-spec [:opts :log :dispatch]))
      (js/console.warn
       (str "Transition not defined from state " (pr-str state)
            " to " (pr-str (:type action)))))
    entry))

(defn- create-transition
  [prev-state next-state action]
  {:prev prev-state
   :next next-state
   :action action
   :at (js/Date.now)})

(defn- normalize-state
  [next-state]
  (let [next-state (if (keyword? next-state)
                     {:state next-state}
                     next-state)
        {:keys [state context effect]
         :or {context {} effect nil}} next-state]
    {:state state
     :context context
     :effect (if (keyword? effect)
               {:id effect}
               effect)}))

(defn transition-state
  "Perform a defined transition from one state to another with an action.
  Intended for internal use or implementing state adapters. Validates the
  resulting state against the spec.

  Arguments:
  - fsm-spec - An fsm-spec hash-map derefed from the `create` function
  - prev-state - A hash-map with :state, :context, and :effect keys
  - action - A hash-map with a :type and arg attrs

  Returns a transition has-map with :prev state :next state and :action
  "
  [fsm-spec prev-state action]
  (let [action (parse-action fsm-spec action)]
    (when-let [transition-entry (get-transition-entry fsm-spec prev-state action)]
      (let [{:keys [reducer allowed-states]} transition-entry
            action (assoc-in action [:meta :created-at] (js/Date.now))
            next-state (->> (reducer prev-state action)
                            (normalize-state)
                            (parse-state fsm-spec))]
        (assert (contains? allowed-states (:state next-state))
                (str "Resulting state "
                     (pr-str (:state next-state))
                     " was not in list of allowed :to states "
                     (pr-str allowed-states)))
        (create-transition prev-state next-state action)))))

(defprotocol IStateMachine
  "A protocol for defining state machines against a spec atom. Supports creating
  adapters for different state systems such as streams, reagent atoms, or
  other state management libraries.

  It is also important to implement cljs.core/IDeref -deref and
  cljs.core/ILookup -lookup methods to add support for deref syntax and common
  get and get-in functions.
  "
  (internal-state
    [machine]
    "Intended for internal use or debugging.

    Arguments:
    - fsm - Instance of a FSM implementing the IStateMachine protocol

    Returns internal state hash-map including the state, cleanup-effect,
    and subscriptions")
  (dispatch
    [machine action]
    "Dispatch actions to invoke defined transitions between states.
    States returned are validated against the fsm spec this machine was
    created against.

    Arguments:
    - fsm - Instance of a FSM implementing the IStateMachine protocol
    - action - Hash-map with a :type and other types matching what an fsm spec
               action

    Returns a transition hash-map with :next, :prev, and :action
    ")
  (subscribe
    [machine listener]
    "Add a listener function to receive transition hash-maps

    Arguments:
    - fsm - Instance of a FSM implementing the IStateMachine protocol
    - listener - A function that accepts transition hash-maps

    Returns a function to unsubscribe the listener from future transitions")
  (destroy
    [machine]
    "Remove all subscriptions, clears any stored state, and stops any running
     effects.

    Arguments:
    - fsm - Instance of a FSM implementing the IStateMachine protocol

    Returns nil"))

(defn run-effect!
  "Given a transition, cancel previous running effects and perform another effect

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - fsm - An instance of the IStateMachine protocol
  - transition - A transition hash-map with :next, :prev, and :action args

  Returns a vector with a keyword like the following:
  [:unchanged function-or-nil]
  [:updated nil]
  [:updated function]
  "
  [fsm-spec-ref fsm transition]
  (let [cleanup-effect (-> fsm (internal-state) (:cleanup-effect))
        prev-effect (get-in transition [:prev :effect])
        next-effect (get-in transition [:next :effect])]
    (cond
      (= prev-effect next-effect)
      [:unchanged cleanup-effect]

      (and (nil? next-effect) (fn? cleanup-effect))
      (do
        (cleanup-effect)
        [:updated nil])

      (nil? next-effect)
      [:updated nil]

      :else
      (do
        (when (fn? cleanup-effect)
          (cleanup-effect))
        (let [spec @fsm-spec-ref
              effect-validator (get-in spec [:validators :effects (:id next-effect)])
              effect-fn (get-in spec [:effects (:id next-effect)])]
          (assert (fn? effect-validator) (str "Effect undefined, got " (pr-str next-effect)))
          (let [result (v/validate effect-validator next-effect)]
            (if (v/valid? result)
              [:updated (effect-fn {:fsm fsm
                                    :state (:next transition)
                                    :action (:action transition)
                                    :effect next-effect
                                    :dispatch #(dispatch fsm %)})]
              (throw (js/Error. (str "Invalid effect " (v/errors->string (:errors result))))))))))))

(defn destroyed?
  "Helper function to determine if a FSM instance was destroyed

  Arguments:
  - fsm - A FiniteStateMachine instance implementing the IStateMachine protocol

  Returns boolean, true if the state is :dev.jaide.finity.core/destroyed"
  [fsm]
  (= (get @fsm :state) ::destroyed))

(defn assert-alive
  "Helper function to assert fsm is alive (not destroyed)

  Arguments:
  - fsm - A FiniteStateMachine instance implementing the IStateMachine protocol

  Returns nil"
  [fsm]
  (assert (not (destroyed? fsm)) "Cannot proceed, this FSM was destroyed"))

(deftype AtomFSM [spec-atom state-atom]
  IStateMachine

  (internal-state [this]
    (assert-alive this)
    @state-atom)

  (dispatch [this action]
    (assert-alive this)
    (let [prev-state @this
          fsm-spec @spec-atom]
      (when-let [transition (transition-state fsm-spec prev-state action)]
        (swap! state-atom assoc :current (:next transition))
        (doseq [subscriber (get @state-atom :subscribers)]
          (subscriber transition))
        (swap! state-atom
               (fn [state]
                 (let [[status cleanup-effect] (run-effect! spec-atom this transition)]
                   (-> (case status
                         :updated (assoc state :cleanup-effect (when (fn? cleanup-effect)
                                                                 cleanup-effect))
                         state)))))
        transition)))

  (subscribe [this listener]
    (assert-alive this)
    (swap! state-atom update :subscribers conj listener)
    (fn unsubscribe
      []
      (swap! state-atom update :subscribers disj listener)))

  (destroy [this]
    (assert-alive this)
    (when-let [cleanup-effect (get @state-atom :cleanup-effect)]
      (cleanup-effect))
    (swap! state-atom merge {:current {:state ::destroyed
                                       :context {}
                                       :effect ::destroyed}
                             :cleanup-effect nil
                             :subscribers #{}})
    this)

  IDeref
  (-deref [_this]
    (:current @state-atom))

  ILookup
  (-lookup [this k]
    (-lookup this k nil))
  (-lookup [this k not-found]
    (get (:context @this) k not-found)))

(defn atom-fsm
  "Create an FSM instance from a spec with an initial state based on an atom.

  Notes:
  - Every transition must be validated
  - Returned FSM can be derefed @fsm as well as (get fsm :current)

  Arguments:
  - spec - An FSM spec atom created with the `fsm/create` function
  - opts - Required hashmap of named options

  Options:
  - initial - A valid initial state hash-map with :state, optional :context
              and :effect attrs

  Returns an instance of FSMAtom
  "
  [spec {:keys [initial effect atom]
         :or {atom atom}}]
  (AtomFSM. spec
            (atom {:current (init spec (merge {:context {}} (:initial @spec) initial) effect)
                   :cleanup-effect nil
                   :subscribers #{}})))

(defn spec->diagram
  "Transform a FSM spec into a Mermaid flowchart diagram

  Arguments:
  - fsm-spec-ref - An FSM spec atom created with the `fsm/create` function
  - opts - Required hashmap of named option

  Options:
  - direction - String should be a mermaid string TD or LR

  Returns a mermaid chart string"
  [fsm-spec-ref & {:keys [direction]
                   :or {direction "TD"}}]
  (let [spec @fsm-spec-ref
        initial (get-in spec [:initial :state])]
    (->> (:transitions spec)
         (mapcat
          (fn [[[state action] {:keys [allowed-states]}]]
            (for [dest allowed-states]
              (str "    " state "-->|" action "| " dest))))
         (s/join "\n")
         (str "flowchart " direction "\n    "
              "init([start])-->" initial "\n"))))

(comment
  (let [xs #{:a :b :c}]
    (disj xs :b)
    (conj xs :d)))
