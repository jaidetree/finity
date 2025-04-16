(ns dev.jaide.finity.core
  (:refer-clojure :exclude [])
  (:require
   [clojure.core :as cc]
   [clojure.string :as s]
   [cljs.core :refer [IDeref ILookup]]
   [dev.jaide.valhalla.core :as v]
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
    :initial     {:value nil
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
        validator (v/record {:value (v/literal id)
                             :context context-validator})]
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
              action or keyword representing.
  
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
      (when (fn? (get-in @fsm-spec-ref [:transitions state-action-vec :handler]))
        (throw (js/Error. (str "Transition already defined for state "
                               (pr-str (first state-action-vec))
                               " and action " (pr-str (second state-action-vec))))))
      (swap! fsm-spec-ref assoc-in [:transitions state-action-vec]
             {:allowed-states (if (fn? f-or-kw)
                                (set to)
                                #{f-or-kw})
              :handler (if (fn? f-or-kw)
                         f-or-kw
                         (fn []
                           {:value f-or-kw}))}))
    fsm-spec-ref))

(defn assert-state
  "Assert a state matches a defined state and passes validation. 
  Mostly intended for internal use or implementing other state adapters.
  
  Arguments:
  - fsm-spec - An fsm-spec hash-map already derefed
  - state - A hash-map with :value keyword and :context hash-map
  
  Returns the parsed output of the state validator
  "
  [fsm-spec state]
  (if-let [validator (get-in fsm-spec [:validators :states (:value state)])]
    (let [result (v/validate validator state)]
      (if (v/valid? result)
        (:output result)
        (throw (js/Error.
                (str "Invalid state\n"
                     (v/errors->string (:errors result)))))))

    (throw (js/Error. (str "Validator not found for state, got "
                           (pr-str state))))))

(defn assert-effect
  "Assert an effect matches a defined effect and passes validation. 
  Mostly intended for internal use or implementing other state adapters.
  
  Arguments:
  - fsm-spec - An fsm-spec hash-map already derefed
  - effect - A hash-map with :id keyword and arg attrs
  
  Returns the parsed output of the effect validator
  "
  [fsm-spec effect]
  (if (nil? effect)
    effect
    (if-let [validator (get-in fsm-spec [:validators :effects (:id effect)])]
      (let [result (v/validate validator effect)]
        (if (v/valid? result)
          (:output result)
          (throw (js/Error. (str "FSMInvalidEffectError: Effect is not valid\n"
                                 (v/errors->string (:errors result)))))))
      (throw (js/Error. (str "FSMInvalidEffectError: Validator not found for effect, got "
                             (pr-str effect)))))))

(defn initial
  "Set default initial state of fsm spec. Can be overwritten in atom-fsm
  function. Must be called after states are defined as state will be validated.
  
  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - state - A state hashmap with :value and :context attrs or value keyword
  
  Returns the fsm-spec-ref atom for chaining"
  [fsm-spec-ref state]
  (let [fsm-spec @fsm-spec-ref
        state (if (keyword? state) {:value state} state)
        state (merge {:context {}} state)
        state (assert-state fsm-spec state)]
    (swap! fsm-spec-ref assoc :initial state)
    fsm-spec-ref))

(defn init
  "Validate an initial state, context, and effect. Intended for internal use
  or implementing new state adapters.

  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - state - A hash-map with :value state keyword and optional :context attr
  - effect - Effect hash-map with :id and arg attrs, needs to match defined 
             effect validator
  
  Returns state hash-map with :value, :context, and :effect keys
  "
  [fsm-spec-ref state & [effect]]
  (assert-fsm-spec fsm-spec-ref)
  (let [fsm-spec  @fsm-spec-ref
        state {:value (:value state)
               :context (:context state)
               :effect effect}]
    (assert-state fsm-spec state)
    (assert-effect fsm-spec effect)
    state))

(defn assert-action
  "Validates an action matches a defined action validator

  Arguments:
  - fsm-spec - An fsm-spec hash-map already derefed
  - action - An action hash-map with a :type and arg attrs

  Returns action hash-map"
  [fsm-spec action]
  (let [validator (get-in fsm-spec [:validators :actions (:type action)])]
    (assert (fn? validator) (str "Action not defined, got " (pr-str action)))
    action))

(defn- get-transition
  [fsm state action]
  (let [transition (get-in fsm [:transitions [state (:type action)]])
        {:keys [handler]} transition]
    (assert (fn? handler) (str "Transition not defined from state " (pr-str state)
                               " to " (pr-str (:type action))))
    transition))

(defn- create-transition
  [fsm-spec-ref prev-state action]
  (let [fsm-spec @fsm-spec-ref
        {:keys [handler allowed-states]} (get-transition fsm-spec (:value prev-state) action)
        next-state (-> prev-state
                       (merge (handler prev-state action)))]
    (assert-state fsm-spec next-state)
    (assert (contains? allowed-states (:value next-state))
            (str "Resulting state "
                 (pr-str (:value next-state))
                 " was not in list of allowed :to states "
                 (pr-str allowed-states)))
    {:prev prev-state
     :next next-state
     :action action
     :at (js/Date.now)}))

(defn prev-state->next-state
  "Perform a defined transition from one state to another with an action. 
  Intended for internal use or implementing state adapters
  
  Arguments:
  - fsm-spec-ref - An fsm-spec atom from the `create` function
  - prev-state - A hash-map with :value, :context, and :effect keys
  - action - A hash-map with a :type and arg attrs

  Returns a transition has-map with :prev state :next state and :action
  "
  [fsm-spec-ref prev-state action]
  (let [spec @fsm-spec-ref
        action (-> (assert-action spec action)
                   (assoc-in [:meta :created-at] (js/Date.now)))
        transition (create-transition fsm-spec-ref prev-state action)]
    transition))

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
  (= (get @fsm :value) ::destroyed))

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
    (let [state @this
          transition (prev-state->next-state spec-atom state action)]
      (swap! state-atom update :state merge (:next transition))
      (doseq [subscriber (get @state-atom :subscribers)]
        (subscriber transition))
      (swap! state-atom (fn [state]
                          (let [[status cleanup-effect] (run-effect! spec-atom this transition)]
                            (case status
                              :updated (assoc state :cleanup-effect (when (fn? cleanup-effect)
                                                                      cleanup-effect))
                              state))))
      transition))

  (subscribe [this listener]
    (assert-alive this)
    (swap! state-atom update :subscribers conj listener)
    (fn unsubscribe
      []
      (swap! state-atom update :subscribers disj listener)))

  (destroy [this]
    (assert-alive this)
    (when-let [cleanup-effect (get @state-atom :cleanup-effect)]
      (println "cleanup" cleanup-effect)
      (cleanup-effect))
    (swap! state-atom merge {:state {:value ::destroyed
                                     :context {}
                                     :effect ::destroyed}
                             :cleanup-effect nil
                             :subscribers #{}})
    this)

  IDeref
  (-deref [_this]
    (:state @state-atom))

  ILookup
  (-lookup [this k]
    (-lookup this k nil))
  (-lookup [this k not-found]
    (get @this k not-found)))

(defn atom-fsm
  "Create an FSM instance from a spec with an initial state based on an atom.

  Notes:
  - Every transition must be validated
  - Returned FSM can be derefed @fsm as well as (get fsm :value)

  Arguments:
  - spec - An FSM spec atom created with the `fsm/create` function
  - opts - Required hashmap of named options

  Options:
  - state - Hash map with :value and optional :context attrs

  Returns an instance of FSMAtom
  "
  [spec {:keys [state effect atom]
         :or {atom atom}}]
  (AtomFSM. spec
            (atom {:state (init spec (merge {:context {}} (:initial @spec) state) effect)
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
        initial (get-in spec [:initial :value])]
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
