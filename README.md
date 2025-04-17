# Finity

<p align="center">
  <img src="./doc/finity-logo.svg" alt="finity Logo" />
</p>

A ClojureScript Finite State Machine library. Finity provides a robust toolkit to model application state transitions with validation, side effects, and a clean API. It also provides an IStateMachine protocol to implement adapters for other state libraries but `atom-fsm` is provided.

## Installation

### deps.edn / Babashka

```clojure
{:deps {dev.jaide/finity {:mvn/version "2025.4.16-SNAPSHOT"}}}
```

### Leiningen / Boot

```clojure
[dev.jaide/finity "2025.4.16-SNAPSHOT"]
```

## Why another FSM library?

While I mostly prefer Clojure, I do admire the sense of correctness and confidence languages like ReScript and TypeScript can provide. This library is an effort to improve the ClojureScript frontend developer experience by using finite-state-machines to validate behavior and my validator library [Valhalla](https://github.com/jaidetree/valhalla) to parse and enforce state correctness. It aims to achieve a pleasant balance between having certainty in the data and state of the system without losing the raw development speed and flexibility of ClojureScript.

While there are other FSM libraries targeting ClojureScript, I felt there were a few key shortcomings I wanted to address:

1. Most states and transitions are not validated, which can lead to extra time debugging what went wrong.
2. Includes extra context data with the state, which is also validated, to support storing real-world data such as mouse event coordinates, request data, selected ids, and other tertiary state.
3. Manage effects for complex interactions such as request promises, event listeners, and timers

## Validation

Note that any API that handles validation is expecting a hash-map mapping keys to Valhalla-compatible validator functions. See the examples below for how to work with them, or check out https://github.com/jaidetree/valhalla for more info.

## Usage

Finity provides a simple yet powerful API for defining and using finite state machines in your ClojureScript applications.

### Defining a FSM Spec

First, create a specification for your state machine:

```clojure
(require
 '[dev.jaide.finity.core :as fsm]
 '[dev.jaide.valhalla.core :as v])

(def fsm-spec (fsm/create :my-machine))
```

### Defining States

Define the valid states for your machine:

```clojure
(-> fsm-spec
    (fsm/state :idle)
    (fsm/state :pending {:url (v/string)})
    (fsm/state :fulfilled {:data (v/hash-map (v/string) (v/string)})})
    (fsm/state :rejected {:message (v/string)}))
```

### Defining Actions

Define the actions that can trigger state transitions:

```clojure
(-> fsm-spec
    (fsm/action :fetch {:url (v/string)})
    (fsm/action :resolve {:data (v/hash-map (v/string) (v/string))})
    (fsm/action :reject {:message (v/string)})
    (fsm/action :reset))
```

### Defining Effects

Define side effects that occur during state transitions:

```clojure
(fsm/effect fsm-spec :fetch-data
  {:url string?}
  (fn [{:keys [effect dispatch]}]
    (-> (js/fetch (:url effect))
        (.then (fn [response] (.json response)))
        (.then (fn [body]
                (dispatch {:type :resolve :data response})))
        (.catch (fn [error]
                 (dispatch {:type :reject :message (.-message error)})))
    ;; Return cleanup function (optional)
    (fn cleanup []
      (println "Cleaning up fetch effect"))))
```

### Defining Transitions

Define how states transition in response to actions:

```clojure
(-> fsm-spec
    (fsm/transition
      {:from [:idle]
       :actions [:fetch]
       :to [:pending]}
      (fn [state action]
        {:value :loading
         :context {:data nil}
         :effect {:id :fetch-data
                  :url (:url action)}}))

    (fsm/transition
      {:from [:loading]
       :actions [:resolve]
       :to [:fulfilled]}
      (fn [state action]
        {:value :fulfilled
         :context {:data (:data action)}}))

    (fsm/transition
      {:from [:loading]
       :actions [:reject]
       :to [:rejected]}
      (fn [state action]
        {:value :rejected
         :context {:message (:message action)}}))

    (fsm/transition
      {:from [:fulfilled :rejected]
       :actions [:reset]} ;; :to is not required when defining keyword transitions
      :idle))

;; Set initial state
(fsm/initial fsm-spec :idle)
```

### Creating an Atom Instance

Create a state machine instance:

```clojure
(def fsm (fsm/atom-fsm fsm-spec))
```

### Dispatching Actions

Trigger state transitions by dispatching actions:

```clojure
(fsm/dispatch fsm {:type :fetch :url "https://api.example.com/data"})
```

### Reading State

Access the current state:

```clojure
;; Using deref
@fsm
;; => {:value :loading, :context {:data nil}, :effect {:id :fetch-data, :url "..."}}

;; Using get
(get fsm :value)
;; => :loading

;; Using get-in
(get-in fsm [:context :data])
;; => #js { "some-key" "some-value" }
```

### Subscribing to State Changes

Listen for state transitions:

```clojure
(def unsubscribe
  (fsm/subscribe fsm
    (fn [transition]
      (println "Transitioned from" (get-in transition [:prev :value])
               "to" (get-in transition [:next :value])
               "via" (get-in transition [:action :type])))))

;; Later, to stop listening:
(unsubscribe)
```

### Cleanup

Properly dispose of the machine when done:

```clojure
(fsm/destroy fsm)
```

## Examples

### Simple Traffic Light

```clojure
(def traffic-light (fsm/create :traffic-light))

(-> traffic-light
    (fsm/state :red)
    (fsm/state :yellow)
    (fsm/state :green)

    (fsm/action :next)

    (fsm/transition
      {:from [:red] :actions [:next] :to [:green]}
      :green)

    (fsm/transition
      {:from [:green] :actions [:next]} ;; :to is not required when transition is a single keyword
      :yellow)

    (fsm/transition
      {:from [:yellow] :actions [:next]}
      :red)

    (fsm/initial :red))

(def light (fsm/atom-fsm traffic-light {:state {:value :red}}))

;; Cycle through the lights
(fsm/dispatch light {:type :next}) ;; => green
(fsm/dispatch light {:type :next}) ;; => yellow
(fsm/dispatch light {:type :next}) ;; => red
```

### Async Data Fetcher

```clojure
(def fetcher (fsm/create :data-fetcher))

(-> fetcher
    (fsm/state :idle)
    (fsm/state :pending {:url (v/string)})
    (fsm/state :fulfilled {:data (v/assert (constantly true))})
    (fsm/state :rejected {:message (v/string)})

    (fsm/action :fetch {:url (v/string)})
    (fsm/action :resolve {:data (v/assert (constantly true))})
    (fsm/action :reject {:message (v/string)})
    (fsm/action :reset)

    (fsm/effect :fetch-data
      {:url string?}
      (fn [{:keys [effect dispatch]}]
        (-> (js/fetch (:url effect))
            (.then #(.json %))
            (.then #(dispatch {:type :resolve :data %}))
            (.catch #(dispatch {:type :reject :message (.-message %)})))
        nil)) ;; No cleanup needed

    (fsm/transition
      {:from [:idle] :actions [:fetch] :to [:pending]}
      (fn [state action]
        {:value :pending
         :context {:url (:url action)}
         :effect {:id :fetch-data :url (:url action)}}))

    (fsm/transition
      {:from [:pending] :actions [:resolve] :to [:fulfilled]}
      (fn [state action]
        {:value :fulfilled
         :context {:data (:data action)}}))

    (fsm/transition
      {:from [:pending] :actions [:reject] :to [:error]}
      (fn [state action]
        {:value :rejected
         :context {:message (:message action)}}))

    (fsm/transition
      {:from [:fulfilled :rejected] :actions [:reset]} ;; :to is not required when transition is a single keyword
      :idle)

    (fsm/initial :idle))

(def data-fetcher (fsm/atom-fsm fetcher))
;; An initial state may be provided on instantiation
;; (def data-fetcher (fsm/atom-fsm fetcher {:state :idle}))

;; Usage
(fsm/dispatch data-fetcher {:type :fetch :url "https://api.example.com/data"})
```

## Implementing Adapters

Finity is designed to be adaptable to different state management systems. You can implement your own adapters by following the `IStateMachine` protocol:

### Get

Implement the `ILookup` and the `IDeref` protocols to allow state access:

```clojure
IDeref
(-deref [this] ...)

ILookup
(-lookup [this k] ...)
(-lookup [this k not-found] ...)
```

### Dispatch

Implement the `dispatch` method to handle actions:

```clojure
(dispatch [this action] ...)
```

### Subscribe

Implement the `subscribe` method to allow listeners:

```clojure
(subscribe [this listener] ...)
```

### Destroy

Implement the `destroy` method for cleanup:

```clojure
(destroy [this] ...)
```

Look at the atom-fsm example in core.cljs for how to implement an adapter.

## Visualizing State Machines

Finity provides a way to generate Mermaid diagrams from your state machines:

```clojure
(println (fsm/spec->diagram fsm-spec))
```

This will output a Mermaid flowchart that you can paste into documentation or a Mermaid-compatible viewer.

## Credits

- Thanks to [@jmezzcappa](https://github.com/jmezzacappa) for coming up with a great TypeScript FSM implementation at Crunchy.
- Thanks to the Doom Emacs discord server for letting me ramble on ideas in the #programming channel :sweat_smile:

## License

Distributed under the GNU-GPL-3.0 license
