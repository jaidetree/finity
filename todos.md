# Todos

## 1. Getting Started

- [x] Create src/main/dev/jaide/finity/core.cljs
- [x] Create src/test/dev/jaide/finity/core_test.cljs
- [x] Create shadow-cljs.edn
- [x] Create package.json

## 2. Implement Defining a FSM Spec

- [x] Implement `create` to create an atom
- [x] Write tests for `create`
- [x] Implement `state` to register a state with optional validators
- [x] Write tests for `state`
- [x] Implement `action` to register an action with optional validators
- [x] Write tests for `action`
- [x] Implement `effect` to register an effect with optional validators
- [x] Write tests for `effect`
- [x] Implement `transition` to register a transition with dynamic body
- [x] Write tests for `transition`
- [x] Update `transition` to support static transitions
- [x] Implement tests for static `transition`
- [x] Ensure all tests for defining a FSM spec are passing

## 3. Implement Reducers for Updating a FSM instance

- [x] Implement `reduce-state*` reducer to calculate new state with value, ctx, and effect
  - [x] Validate action
  - [x] Check if transition is supported
  - [ ] If transition found but it's a keyword, update state value
  - [x] If transition not found and exhaustive is enabled, throw error for unhandled state
  - [ ] If state returned is a keyword, update state value
  - [x] If hash-map returned assume state, context, and optional effect
- [x] Write tests for `reduce-state*` reducer
- [x] Implement `run-effect!` helper
- [x] Write tests for `run-effect!`
- [x] Ensure all tests for reduce-state and run-effect! are passing

## 4. Implement IStateMachine Protocol

- [x] Define `dispatch` function
- [x] Write docstr for `dispatch`
- [x] Define `internal-state` function
- [x] Write docstr for `internal-state`
- [x] Define `subscribe` function
- [x] Write docstr for `subscribe`
- [x] Define `destroy` function
- [x] Write docstr for `destroy`

## 5. Implement AtomFSM Using the IStateMachine Protocol

- [x] Define AtomFSM deftype
- [x] Implement internal-state
- [x] Implement dispatch
- [x] Implement subscribe
- [x] Implement destroy
- [x] Write tests for internal-state
- [x] Write tests for dispatch
- [x] Write tests for subscribe
- [x] Write tests for destroy

## 6. Generate Mermaid charts

- [x] Update define transition API to require vector of return states
- [x] Update reduce state to ensure returned state included in vector of supported states
- [x] Define function to transform a spec into a mermaid chart string
- [x] Write test to ensure expected mermaid chart string
- [x] Ensure all affected tests pass

## 7. Enhance graphs

- [x] Define initial function to set initial state of spec
- [x] Write unit test for initial function
- [x] Support initial state overrides when creating atom-fsm instance
- [x] Update spec->diagram function to indicate starting state
- [x] Ensure all affected tests pass

## 7. Prepare project for publishing

- [ ] Design logo
- [x] Update readme.md
- [x] Copy versioning script from valhalla
- [x] Generate pom file
- [x] Update version

## 8. Publish

- [x] Add https://github.com/liquidz/build.edn
- [x] Configure CI
- [x] Set env vars
- [x] Draft a release
- [ ] Deploy to clojars
- [ ] Announce in Slack
