(ns dev.jaide.finity.core-test
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.test :refer [async deftest testing is]]
   [dev.jaide.finity.core :as fsm]
   [dev.jaide.valhalla.core :as v]))

(deftest create-test
  (testing "create"
    (testing "Returns a fsm atom hash-map"
      (let [fsm @(fsm/create :test-fsm)]
        (is (map? fsm) "FSM was not a hash-map")))
    (testing "supports custom atom function"
      (let [fsm (fsm/create :test-fsm {:atom identity})]
        (is (map? fsm))))))

(deftest state-test
  (testing "state"
    (testing "without context"
      (let [fsm (fsm/create :test-fsm)
            fsm (fsm/state fsm :idle)
            validator (get-in @fsm [:validators :states :idle])]
        (is (fn? validator))
        (is (v/valid? (v/validate validator {:value :idle
                                             :context {}})))))
    (testing "with context"
      (let [fsm (fsm/create :test-fsm)
            fsm (fsm/state fsm :pending {:url (v/string)})
            validator (get-in @fsm [:validators :states :pending])]
        (is (fn? validator))
        (is (v/valid? (v/validate validator {:value :pending
                                             :context {:url "https://example.com"}})))))
    (testing "throws error if state already defined"
      (let [fsm (fsm/create :test-fsm)
            fsm (fsm/state fsm
                           :pending
                           {:url (v/string)})]
        (is (thrown? :default (fsm/state fsm :pending {:url (v/number)})))))))

(deftest register-action-test
  (testing "register-action"
    (testing "define action"
      (let [fsm (fsm/create :test-fsm)
            fsm (fsm/action fsm
                            :run-test
                            {:test-id (v/string)})
            validator (get-in @fsm [:validators :actions :run-test])]
        (is (fn? validator))
        (is (v/valid? (v/validate validator {:type :run-test
                                             :test-id "this-test"})))))
    (testing "throws error if already defined"
      (let [fsm (fsm/create :test-fsm)
            fsm (fsm/action fsm
                            :run-test
                            {:test-id (v/string)})]
        (is (thrown? :default (fsm/action fsm :run-test {:test-id (v/number)})))))))

(defn test-fsm
  []
  (-> (fsm/create :test-fsm)
      (fsm/state :green-light)
      (fsm/state :yellow-light)
      (fsm/state :red-light)
      (fsm/action :change-light)))

#_(def test-fsm
    (-> (fsm/create)
        (fsm/def-state :green-light)
        (fsm/def-state :yellow-light)
        (fsm/def-state :red-light)
        (fsm/def-action :change-light)))

(deftest register-transition-test
  (testing "register-transition"
    (testing "define map of transitions"
      (let [fsm (test-fsm)
            fsm (fsm/transition
                 fsm
                 {:from [:green-light :yellow-light :red-light]
                  :actions [:change-light]
                  :to [:green-light :red-light :yellow-light]}
                 (fn [{:keys [state _context _effect]} action]
                   (case [state action]
                     [:green-light :change-light] {:state :yellow-light}
                     [:yellow-light :change-light] {:state :red-light}
                     [:red-light :change-light] {:state :green-light})))
            fsm @fsm]
        (is (fn? (get-in fsm [:transitions [:green-light :change-light] :handler])))
        (is (fn? (get-in fsm [:transitions [:yellow-light :change-light] :handler])))
        (is (fn? (get-in fsm [:transitions [:red-light :change-light] :handler])))))

    (testing "throws error on overlapping defs"
      (let [fsm (test-fsm)]
        (fsm/transition
         fsm
         {:from [:yellow-light]
          :actions [:change-light]
          :to [:red-light]}
         (fn [{:keys [state _context _effect]} _action]
           {:state :red-light}))
        (is
         (thrown? :default
                  (fsm/transition
                   fsm
                   {:from [:yellow-light]
                    :actions [:change-light]
                    :to [:red-light]}
                   (fn [{:keys [state _context _effect]} _action]
                     {:state :red-light}))))))

    (testing "throws error if state was not defined"
      (let [fsm (test-fsm)]
        (is
         (thrown? :default
                  (fsm/transition
                   fsm
                   {:from [:purple-light]
                    :actions [:change-light]
                    :to [:red-light]}
                   (fn [{:keys [state _context _effect]} action]
                     {:state :red-light}))))))

    (testing "throws error if action was not defined"
      (let [fsm (test-fsm)]
        (is
         (thrown? :default
                  (fsm/transition
                   fsm
                   {:from [:green-light :yellow-light :red-light]
                    :actions [:flash-light]
                    :to [:green-light :yellow-light :red-light]}
                   (fn [{:keys [_state _context _effect]} _action]
                     {:state :red-light}))))))))

(deftest effect-test
  (testing "register-effect"
    (testing "Registers effect-handler without arg validator"
      (let [fsm (fsm/create :ctx-test-fsm)]
        (fsm/effect
         fsm :start-timer
         (fn [{:keys [_effect _state _action dispatch]}]
           (let [timer (js/setInterval #(dispatch {:type :tick
                                                   :timestamp (js/Date.now)}))]
             (fn cleanup-tmer
               []
               (js/clearInterval timer)))))
        (let [fsm @fsm
              validator (get-in fsm [:validators :effects :start-timer])]
          (is (fn? (get-in fsm [:effects :start-timer])))
          (is (fn? (get-in fsm [:validators :effects :start-timer])))
          (is (v/valid? (v/validate validator {:id :start-timer}))))))
    (testing "Registers effect-handler with arg validator"
      (let [fsm (fsm/create :ctx-test-fsm)]
        (fsm/effect
         fsm :fetch
         {:url (v/string)}
         (fn [_]
           nil))
        (let [fsm @fsm
              validator (get-in fsm [:validators :effects :fetch])]
          (is (fn? (get-in fsm [:effects :fetch])))
          (is (fn? (get-in fsm [:validators :effects :fetch])))
          (is (v/valid? (v/validate validator {:id :fetch :url "https://example.com"}))))))))

(defn create-fsm-with-ctx
  []
  (let [fsm (fsm/create :ctx-test-fsm)]
    (fsm/state fsm :idle)
    (fsm/state fsm :fulfilled {:data (v/assert #(do true))})
    (fsm/state fsm :pending {:url (v/string)})
    (fsm/action fsm :fetch {:url (v/string)})
    (fsm/action fsm :complete {:data (v/assert #(do true))})
    (fsm/effect fsm :fetch {:url (v/string)}
                (fn [{:keys [effect]}]
                  effect))
    (fsm/transition fsm
                    {:from [:idle]
                     :actions [:fetch]
                     :to [:pending]}
                    (fn [{:keys [_value _context]} action]
                      {:value :pending
                       :context {:url (:url action)}
                       :effect {:id :fetch :url (:url action)}}))
    (fsm/transition fsm
                    {:from [:pending]
                     :actions [:complete]
                     :to [:fulfilled]}
                    (fn [state action]
                      {:value :fulfilled
                       :context (:data action)
                       :effect nil}))
    fsm))

(deftest initial-test
  (testing "initial"
    (testing "sets initial state on fsm spec"
      (let [spec-atom (create-fsm-with-ctx)]
        (fsm/initial spec-atom {:value :idle})
        (let [spec @spec-atom]
          (is (= (:initial spec) {:value :idle
                                  :context {}})))))))

(deftest define-test
  (testing "define"
    (testing "defines an entire fsm-spec with one function call"
      (let [spec {:id :define-test-fsm
                  :state   {:value :idle
                            :context {}
                           ;; For example if wanting to start with an effect running
                            :effect {:id :fetch
                                     :url "/api/tasks"}}

                  :states  {:idle {}
                            :loading {:url (v/string)}
                            :fulfilled {:data (v/hash-map (v/string) (v/string))}}

                  :actions {:fetch {:url (v/string)}
                            :fetched {:data (v/hash-map (v/string) (v/string))}
                            :reset {}
                            :error {:error (v/instance js/Error)}}

                  :effects {:fetch [{:url (v/string)}
                                    (fn [{:keys [dispatch effect]}]
                                      (-> (js/fetch (:url effect))
                                          (.then #(.json %))
                                          (.then #(dispatch {:type :fetched :data %}))
                                          (.catch #(dispatch {:type :error :error %}))))]}
                  :transitions
                  [{:from [:idle]
                    :actions [:fetch]
                    :to [:loading]
                    :do (fn [state action]
                          {:value :loading
                           :context {:url (:url action)}
                           :effect {:id :fetch :url (:url action)}})}]}]
        (try
          (is (fsm/define spec))
          (catch :default error
            (js/console.error error)
            (throw error)))))))

(deftest init-test
  (testing "init"
    (testing "parses a valid initial state"
      (let [fsm (create-fsm-with-ctx)
            state (fsm/init fsm {:value :pending
                                 :context {:url "https://example.com"}})]
        (is (= (:value state) :pending))
        (is (= (get-in state [:context :url]) "https://example.com"))))
    (testing "throws error if invalid context"
      (let [fsm (create-fsm-with-ctx)]
        (is (thrown? :default (fsm/init fsm {:value :idle
                                             :context {:website "https://example.com"}})))))
    (testing "throws error if invalid state"
      (let [fsm (create-fsm-with-ctx)]
        (is (thrown? :default (fsm/init fsm {:value :idle
                                             :context {:url "https://example.com"}})))))
    (testing "provides useful error message"
      (let [fsm (create-fsm-with-ctx)
            error (try (fsm/init fsm {:value :pending
                                      :context {:url :url}})
                       (catch :default err
                         (.-message err)))]
        (is (= error
               "Invalid state\ncontext.url: Expected string, got :url"))))))

(deftest atom-fsm-test
  (testing "atom-fsm"
    (testing "Wraps an atom that implements deref"
      (let [fsm (fsm/atom-fsm
                 (create-fsm-with-ctx)
                 {:state {:value :idle}})
            state @fsm]
        (is (= (:value state) :idle))
        (is (= (:context state) {}))
        (is (= (:effect state) nil))))))

(deftest internal-state-test
  (testing "internal-state"
    (testing "Returns full internal state"
      (let [fsm (fsm/atom-fsm
                 (create-fsm-with-ctx)
                 {:state {:value :idle}})
            state (fsm/internal-state fsm)]
        (is (= (-> state :state :value) :idle))
        (is (= (-> state :state :context) {}))
        (is (= (-> state :state :effect) nil))))))

(deftest dispatch-test
  (testing "dispatch"
    (testing "defined transitions return a valid state"
      (let [fsm (fsm/atom-fsm
                 (create-fsm-with-ctx)
                 {:state {:value :idle}})
            _ (fsm/dispatch fsm {:type :fetch :url "https://example.com"})
            state @fsm]
        (is (= (:value state) :pending))
        (is (= (:context state) {:url "https://example.com"}))
        (is (= (:effect state) {:id :fetch :url "https://example.com"}))))))

(deftest subscribe-test
  (testing "subscribe"
    (testing "dispatched events are broadcast to subscribers"
      (let [transaction (atom {})
            fsm (fsm/atom-fsm
                 (create-fsm-with-ctx)
                 {:state {:value :idle}})]
        (fsm/subscribe
         fsm
         (fn [tx]
           (reset! transaction tx)))
        (fsm/dispatch fsm {:type :fetch :url "https://example.com"})
        (let [{:keys [prev next action]} @transaction]
          (is (= (:type action) :fetch))
          (is (= (:url action) "https://example.com"))
          (is (= (:value prev) :idle))
          (is (= (:context prev) {}))
          (is (= (:effect prev) nil))
          (is (= (:value next) :pending))
          (is (= (:context next) {:url "https://example.com"}))
          (is (= (:effect next)  {:id :fetch :url "https://example.com"})))))))

(deftest unsubscribe-test
  (testing "unsubscribe"
    (testing "removes subscription from fsm"
      (let [transaction (atom {})
            fsm (fsm/atom-fsm
                 (create-fsm-with-ctx)
                 {:state {:value :idle}})
            unsubscribe (fsm/subscribe
                         fsm
                         (fn [tx]
                           (reset! transaction tx)))]
        (fsm/dispatch fsm {:type :fetch :url "https://example.com"})
        (unsubscribe)
        (fsm/dispatch fsm {:type :complete :data {}})
        (let [{:keys [prev next action]} @transaction]
          (is (= (:type action) :fetch))
          (is (= (:url action) "https://example.com"))
          (is (= (:value prev) :idle))
          (is (= (:context prev) {}))
          (is (= (:effect prev) nil))
          (is (= (:value next) :pending))
          (is (= (:context next) {:url "https://example.com"}))
          (is (= (:effect next)  {:id :fetch :url "https://example.com"})))))))

(deftest destroy-test
  (testing "destroy"
    (testing "removes subscriptions and unsets state, context, and effect"
      (let [fsm (fsm/atom-fsm
                 (create-fsm-with-ctx)
                 {:state {:value :idle}})
            transactions (atom [])]
        (fsm/subscribe fsm
                       (fn [tx]
                         (swap! transactions conj tx)))
        (fsm/dispatch fsm {:type :fetch :url "https://example.com"})
        (fsm/destroy fsm)
        (is (thrown? :default (fsm/dispatch fsm {:type :complete :data {}})))
        (let [state @fsm]
          (is (= (count @transactions) 1))
          (is (= (:value state) :dev.jaide.finity.core/destroyed))
          (is (= (:context state) {}))
          (is (= (:effect state) :dev.jaide.finity.core/destroyed)))))))

(defn create-counter-fsm
  []
  (let [fsm (fsm/create :ctx-counter-fsm)]
    (fsm/state fsm :active {:num (v/number)})
    (fsm/state fsm :completed {:num (v/number)})
    (fsm/action fsm :increment)
    (fsm/action fsm :complete)
    (fsm/effect fsm :increment-again
                (fn [{:keys [dispatch]}]
                  (dispatch {:type :increment})
                  (dispatch {:type :complete})))
    (fsm/transition fsm
                    {:from [:active]
                     :actions [:increment]
                     :to [:active]}
                    (fn [state _action]
                      {:value :active
                       :context {:num (inc (get-in state [:context :num]))}
                       :effect {:id :increment-again}}))
    (fsm/transition fsm
                    {:from [:active]
                     :actions [:complete]
                     :to [:completed]}
                    (fn [state _action]
                      {:value :completed
                       :context {:num (get-in state [:context :num])}
                       :effect nil}))
    fsm))

(deftest run-effect-test
  (testing "run-effect!"
    (testing "side-effects can run and dispatch multiple actions"
      (let [fsm (fsm/atom-fsm
                 (create-counter-fsm)
                 {:state {:value :active
                          :context {:num 0}}})
            promise (js/Promise.
                     (fn [resolve]
                       (fsm/subscribe fsm
                                      (fn [{:keys [next]}]
                                        (when (= (:value next) :completed)
                                          (resolve next))))))]
        (fsm/dispatch fsm {:type :increment})
        (async done
               (-> promise
                   (.then (fn [state]
                            (is (= (:value state) :completed))
                            (is (= (:context state) {:num 2}))
                            (is (= (:effect state)  nil))
                            (done)))))))))

(defn traffic-fsm
  []
  (-> (fsm/create :traffic-fsm)
      (fsm/state :red-light)
      (fsm/state :yellow-light)
      (fsm/state :green-light)
      (fsm/initial :green-light)
      (fsm/action :change-light)

      (fsm/transition
       {:from [:red-light]
        :actions [:change-light]}
       :green-light)
      (fsm/transition
       {:from [:green-light]
        :actions [:change-light]}
       :yellow-light)
      (fsm/transition
       {:from [:yellow-light]
        :actions [:change-light]}
       :red-light)))

(deftest spec->diagram-test
  (testing "spec->diagram"
    (let [diagram (fsm/spec->diagram (traffic-fsm))]
      (js/console.log diagram)
      (is (= diagram
             "flowchart TD\n    init([start])-->:green-light\n    :red-light-->|:change-light| :green-light\n    :green-light-->|:change-light| :yellow-light\n    :yellow-light-->|:change-light| :red-light")))))

(deftest full-example-test
  (testing "parses a full example"
    (let [task (v/record {:id (v/string)
                          :title (v/string)})
          spec
          {:id :router
           :state {:value :empty
                   :context {:error nil}}

           :states {:empty {:error (v/nilable (v/instance js/Error))}
                    :loading {:url (v/string)}
                    :tasks {:tasks (v/vector task)}}

           :actions {:fetch {:url (v/string)}
                     :fetched {:tasks (v/vector task)}
                     :error {:error (v/instance js/Error.)}}

           :effects {:fetch [{:url (v/string)}
                             (fn [{:keys [dispatch effect]}]
                               (dispatch {:type :fetched :tasks [{:id "test-id"
                                                                  :title "Task Title"}]}))]}

           :transitions
           [{:from [:empty]
             :actions [:fetch]
             :to [:loading]
             :do (fn [state action]
                   {:value :loading
                    :context {:url (:url action)}
                    :effect {:id :fetch :url (:url action)}})}

            {:from [:loading]
             :actions [:fetched]
             :to [:tasks]
             :do (fn [state action]
                   {:value :tasks
                    :context {:tasks (:tasks action)}})}

            {:from [:loading]
             :actions [:error]
             :to :empty}]}]
      (try
        (let [fsm-spec (fsm/define spec)
              fsm (fsm/atom-fsm fsm-spec {})]
          (is (= @fsm {:value :empty
                       :context {:error nil}
                       :effect nil}))
          (fsm/dispatch fsm {:type :fetch :url "https://example.com"})
          (is (= @fsm {:value :loading
                       :context {:url "https://example.com"}
                       :effect {:id :fetch :url "https://example.com"}})))
          
        (catch :default error
          (js/console.error error)
          (throw error))))))
(comment
  #_(def test-fsm
      (-> (fsm/create)
          (fsm/def-state :green-light)
          (fsm/def-state :yellow-light)
          (fsm/def-state :red-light)
          (fsm/def-action :change-light)

          (fsm/def-transition
            {:states [:green-light :yellow-light :red-light]
             :actions [:change-light]}
            (fn [{:keys [state _context _effect]} action]
              (case [state action]
                [:green-light :change-light] {:state :yellow-light
                                              :effect (fn [{:keys [dispatch signal]}]
                                                        (let [timer (js/setTimeout
                                                                     #(dispatch {:type :change-light})
                                                                     (* 2 60))]
                                                          (on-abort signal
                                                                    (js/clearTimeout timer))))}
                [:yellow-light :change-light] {:state :red-light
                                               #_#_:effect ...}
                [:red-light :change-light] {:state :green-light
                                            #_#_:effect ...}))))))
