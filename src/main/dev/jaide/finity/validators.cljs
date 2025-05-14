(ns dev.jaide.finity.validators
  (:require
   [dev.jaide.valhalla.core :as v]))

(def validator-map
  (v/hash-map (v/keyword) (v/assert fn?)))

(def states-validator
  (v/hash-map (v/keyword) (v/union (v/literal {})
                                   validator-map)))

(def actions-validator
  (v/hash-map (v/keyword)
              (v/union (v/literal {})
                       validator-map)))

(def effects-validator
  (v/hash-map (v/keyword)
              (v/union
               (v/assert fn?)
               (v/record
                {:args (v/nilable validator-map)
                 :do (v/assert fn?)}))))

(def transitions-validator
  (v/vector
   (v/union
    (v/record
     {:from (v/vector (v/keyword))
      :actions (v/vector (v/keyword))
      :to (v/vector (v/keyword))
      :do (v/assert fn?)})
    (v/record
     {:from (v/vector (v/keyword))
      :actions (v/vector (v/keyword))
      :to (v/keyword)}))))

(def define-validator
  (v/record
   {:id (v/keyword)
    :initial (v/record {:state (v/keyword)
                        :context (v/assert map?)
                        :effect (v/nilable
                                 (v/chain
                                  (v/record {:id (v/keyword)})
                                  (v/assert map?)))})
    :states states-validator
    :actions actions-validator
    :effects effects-validator
    :transitions transitions-validator}))


