# effects-playground

Playing around with ways to isolate side effects

## Examples

### User creation

The main example is a simple user creation function with the following logic:

```clj
(defn create-user [user]
  (if (validate user) ; against an external service (e.g. zip code validation)
    (do
      (write-to-db user)
      (send-updated-event user)
      (respond :ok))
    (respond :invalid)))
```

The following implementations are included:

* [imperative](src/effects_playground/imperative.clj) (essentially the same as
  the example code)
* [dependency injection](src/effects_playground/inject.clj)
* [mount](src/effects_playground/mount.clj)
* [re-frame-like events and effects](src/effects_playground/re-frame.clj)
* [free monad](src/effects_playground/monad.clj)
* [conditions system](src/effects_playground/condition.clj)
* [single `fx!` function](src/effects_playground/fx_function.clj)
* [`FX` protocols](src/effects_playground/fx_protocol.clj)

### Train reservation

A train reservation system, based on <https://github.com/QuentinDuval/HaskellTrainReservationKata>.

* [imperative](src/effects_playground/train/imperative.clj)
* [free monad](src/effects_playground/train/monad.clj) (a translation of the
  original haskell to clojure)
* [`fx!` function](src/effects_playground/train/fx_function.clj)


## Discussion

### Direct/imperative style

Imperative effectful code is easy to read and write -- simple enough that the
example is almost identical to the actual imperative implementation. However,
it makes it difficult to clearly identify functions that will have side
effects, and does nothing to isolate those effects. You can adopt conventions,
like "side-effecting functions end in `!`", or "effects should only happen at
at the edges" (e.g. [imperative shell, functional core](#reading-boundaries)),
but that requires a shared agreement and discipline to stick to it -- it's easy
to slip-up and stick a side effect 5 functions deep since side-effecting
functions look just like ordinary functions.

Additionally, testing is complicated: you end up having to use `with-redefs`:

```clj
(with-redefs [side-effect-a (fn [a] ,,,)
              side-effect-b (fn [b] ,,,)
              ,,,]
  (is (= ,,, (your-function input))))
```

While `with-redefs` can be useful as a crutch, it creates some problems, e.g.

1. It is fragile: if you add `side-effect-c` somewhere in the chain of calls
   made by `your-function` but you forget to redefine it in your test, your
   test may break (or worse: it might not until later). The error could be
   obvious, or it result in a test that simply hangs, with no indication of
   what went wrong.

2. It doesn't compose well: you can't easily redefine all the side effects for
   one test, then change a single side effect for a second test without a bunch
   of nested calls to `with-redefs`.

#### Imperative variations

The implementations based on [`mount`][mount] and dependency injection look
similar to the standard imperative code. Unfortunately, the testing code is
almost the same as well: you still have to redefine every function, and while
both of these options make composition a little easier, it's still not simple.

Dependency injection calls out side effects better than the standard imperative
code at least: if you want to perform an effect 5 functions deep, you have to
inject the effect function down through all the intermediate functions. Deeply
nested side-effecting code ends up looking bad enough that it encourages
isolating effects to one or two functions. It also avoids problem 1 described
above: since side-effecting functions are injected, any new side effects change
the arity of the calling function.

It is also possible to write protocols (e.g. `UserValidator`, `UserDatabase`,
`MessageQueue`) , and inject records or types that implement these protocols
rather than injecting functions. This has some advantages over function
injection, particularly in terms of organization and establishing clear
boundaries between systems. But injecting multiple records doesn't address long
function signatures, nor does it make test mocks any easier.

I'll come back to dependency injection at the end of this discussion, since it
remains a valuable tool despite its shortcomings.

### Continuations and DSLs

Continuations allow breaking each step of execution into a separate function.
This makes it easier to inspect the execution flow and to change its behavior.
Unfortunately, continuation-passing style can be verbose and confusing without
additional syntax to hide the extra function calls.

Features like [promises][promises], [async/await][async-await],
[coroutines][coroutines], and [generators][generators] are all ways of managing
control flow using continuations. They also make it easy to write code using
continuations that _looks_ imperative. See also
[clojure.core.async][clojure-async] (communicating sequential processes), which
uses a similar model of execution, but with channels for passing data instead
of continuations.

```js
// (1) continuation passing (aka callbacks)
sideEffect1(function(result1) {
  sideEffect2(result1, function(result2) {
    sideEffect3(result2)
  })
})

// (2) promises
sideEffect1().then(sideEffect2).then(sideEffect3)

// (3) async/await
result1 = await sideEffect1();
result2 = await sideEffect2(result1);
result3 = await sideEffect3(result2);
```

It is possible to use the delayed-execution semantics afforded by continuations
to describe effects as a series of instructions. These instructions form a
domain-specific language (DSL), and can be chained together into an abstract
syntax tree (AST). Instead of directly executing instructions to produce
effects, you write an interpreter for your DSL.

```clj
(defn create-user-effects [user]
  [[:validate-user user]
   [:write-to-db user]
   [:send-updated-event user]
   [:respond :ok]])

;; but notice that we can't include conditionals with a pure data DSL:
(if-let [user [:validate-user user]]
  [:respond :valid]
  [:respond :invalid])
```

#### Re-frame

The example DSL above is similar to how re-frame isolates the description of
effects from their execution. In order to implement control flow, re-frame has
you dispatch additional events (essentially continuations):

```clj
(defn step-1 [user]
  {:validate-user user
   :dispatch [::create-user-step-2 user]})    ; <- :dispatch = continuation

(defn step-2 [user]
 (if user
   {:db (add-user user)
    :dispatch-n [[::send-updated-event user]  ; <- more continuations
                 [::send-response :ok]]}
   {:dispatch [[::send-response :invalid]]}))

(re-frame/reg-event-fx ::create-user-step-1 (fn [_ [_ user]] (step-1 user)))
(re-frame/reg-event-fx ::create-user-step-2 (fn [_ [_ user]] (step-2 user)))
```

These functions return plain data, making testing straightforward:

```clj
;; e.g. an invalid user
(let [user {,,,}
      result1 (step-1 user)
      result2 (step-2 result1)]
  (is (= {:validate-user user, :dispatch [::create-user-step-2 user]}
          result1))
  (is (= {:dispatch [:send-response :invalid]}
         result2)))
```

More complex control flow can be described as plain data using
[re-frame-async-flow-fx][re-frame-async-flow-fx], which uses a state machine to
coordinate dispatching multiple dependent events. While both ordinary event
dispatch and async-flow-fx are effective at describing and isolating effects,
they can be particularly verbose and confusing compared to the equivalent
imperative code.

#### Free monads

Haskell uses monads for a variety of things, including side effects. The topic
of monads is well beyond the scope of this discussion (see [further
reading](#free-monads-and-dsls)), but suffice it to say that Free monads can be
used in Haskell to implement _embedded_ DSLs (aka eDSLs), which use the full
power of the host language for control flow.

One way to do this is to use a pair with an instruction and a continuation:

```clj
(defrecord Effect [instruction continuation])
(defrecord ValidateUser [user])
(defrecord WriteToDb [user])
(defrecord SendUpdatedEvent [message])
(defrecord Respond [message])

;; you can sort of read the ->Effect constructor as `>>=` aka `bind`
(defn create-user [user]
  (->Effect (->ValidateUser user) ; instruction
            (fn [user]            ; continuation
              (if user
                (->Effect (->WriteToDb user) ; instruction
                          (fn [result]       ; continuation
                            (->Effect (->SendUpdatedEvent user)) ; instruction
                                      (fn [_]                    ; continuation
                                        (->Effect (->Respond :ok) nil))))
                (->Effect (->Respond :invalid) nil)))))
```

Haskell's `do` notation for monads compiles to the same thing as the above
code, but is easier to read and write, since it appears to be imperative (the
same could be done in clojure using a macro):

```hs
-- roughly translated
createUser user =
  do user <- ValidateUser user
     if user
       then do result <- WriteToDb user
               SendUpdatedEvent user
               Respond "ok"
       else Respond "invalid"
```

Like the re-frame example, this builds a representation of effects without
executing them. In order to execute effects, you then write an interpreter (see
the example code). Interpreters can be written to execute the same DSL with
different behaviors in different contexts (production, testing, tracing, etc.).

### Conditions system

Conditions are used in Common Lisp in lieu of exceptions. Conditions and
exceptions are related, in that both interrupt the normal flow of execution and
jump to a handler. Unlike exceptions, condition handlers can return and _resume
execution_ from where the condition was raised. There are a few clojure
libraries that emulate conditions; one of the simplest is [special][special].

```clj
;; with exceptions
(defn divide [x y]
  (if (zero? y)
    (throw (ex-info "Divide by zero" {:divisor y}))
    (/ x y)))

(try
  (divide x y)
  (catch clojure.lang.ExceptionInfo
    ;; try again with a very small number
    (divide x 0.00000000001)))

;; with special
(require '[special.core :refer [condition manage]])

(defn divide' [x y]
  (let [y (if (zero? y)
            (condition :divide-by-zero {:divisor y}) ; "throw"
            y)]
    (/ x y)))

(let [f (manage divide'
          ;; "catch" and resume
          :divide-by-zero (constantly 0.00000000001))]
  (f x y))
```

If you squint, resuming from a condition is similar to yielding in a coroutine.
We can use this to write a DSL interpreter without having to build the AST
first:

```clj
;; Nearly identical to the original imperative function
(defn create-user [user]
  (if (condition :validate user)
    (do
      (condition :write-to-db user)
      (condition :send-updated-event user)
      (condition :respond :ok))
    (condition :respond :invalid)))

;; A "tracing" interpreter, perhaps for a test
(let [log-and-return #(doto % println)
      f (manage create-user
          :validate (partial log-and-return :validate)
          :write-to-db (partial log-and-return :write-to-db)
          :send-updated-event (partial log-and-return :send-updated-event)
          :respond (partial log-and-return :respond))]
  (println "== valid ==")
  (f {:name "George"})
  (println "== invalid ==")
  (f nil))
; == valid ==
; :validate {:name "George"}
; :write-to-db {:name "George"}
; :send-updated-event {:name "George"}
; :respond :ok
; == invalid ==
; :validate nil
; :respond :invalid
```

This is less pure than the DSL approach, but it has the advantage of being
nearly as easy to read and write as the imperative code, while being much
better about calling out side effects. As with the DSL approach, effects are
executed by an interpreter and are represented as pure data (a keyword and
arguments).

On the other hand, most of the literature around conditions is geared toward
using it as an alternative to exceptions, and it's a little strange to use
exceptions to implement effects. And as with exceptions, there's nothing to
prevent a side-effecting call to `condition` 5 functions deep.

### Single effect handler

For lack of a better term, I'm calling the last two options a "single effect
handler". The first example is nearly the same as the condition system, except
(a) it uses dependency injection instead of a global function, and (b) the
terminology is geared toward effects:

```clj
(defn create-user [fx! user]
  (if (fx! :validate user)
    (do
      (fx! :write-to-db user)
      (fx! :send-updated-event user)
      (fx! :respond :ok))
    (fx! :respond :invalid)))
```

This approach combines most of the benefits of dependency injection, DSLs, and
conditions: the code is simple and straightforward, effects can be represented
as data, an interpreter is used to execute effects, effects are clearly
identified, and if you want to perform effects 5 functions deep, you have to
inject the `fx!` function through all intermediate functions. As with
conditions, this approach loses the purity of continuation-based DSLs. Given
that this is intended to be side-effecting code, and clojure doesn't have a
type system that restricts where effects can happen, this feels like a
reasonable compromise between structure, simplicity, and expressivity.

An additional benefit of using a single injected function is that it lends
itself well to composition via middleware. While it would be possible to build
a middleware-like system using other approaches, the code required for
middleware is very simple in this case:

```clj
;; tracing middleware
(defn wrap-trace [fx!]
  (fn [k & args]
    (println "fx!" k args)
    (let [result (apply fx! k args)]
      (println "=>" result)
      result)))
```

#### Protocol-based variation

This idea can be extended to work with protocols, which adds a little bit of
safety and error-checking:

```clj
;; single effects record that implements several protocols
(defrecord EffectsContext []
  UserValidator
  (validate-user! [this user] ,,,)
  UserDatabase
  (write-user! [this user] ,,,)
  MessageQueue
  (publish! [this queue message] ,,,))

(defn create-user [ctx user]
  (if (validate-user! ctx user)
    (do
      (write-user! ctx user)
      (publish! ctx "updated" user)
      (publish! ctx "response" :ok))
    (publish! ctx "respones" :invalid)))
```

Rather than an `fx!` function being passed around, instead we pass around a
`ctx` record that implements all the effects protocols. Because this approach
uses protocol methods, you get compile-time arity checking. Side effects aren't
called out quite as clearly as with a standard `fx!` function, and they look
less like a data-oriented DSL, but the single required `ctx` argument does make
it clear something out of the ordinary is going on.

Middleware is possible, though a little more complicated. However, _stateful_
middleware may be simpler to reason about than with the `fx!` function, since
every effectful protocol method takes the same `ctx` record, so you can attach
state -- atoms -- to the context (see the implementation). Though it would be
possible to do something similar with metadata on the `fx!` function.

Another possibility would be to use a single `invoke` protocol method to handle
effects. [Cognitect's aws library][cognitect-aws] uses this approach.


## Conclusions and Benefits

Why bother going through all the trouble to isolate side effects? After all,
Clojure is not a purely functional language, and side effects are allowed to
happen at any time, in any function.

### Managing complexity

Isolating side effects is primarily about reducing complexity and making code
easier to reason about. State and effects are two major sources of complexity,
and Clojure goes to great lengths to reduce the complexity of state with
immutable data structures and special forms and functions for interacting with
mutable state (atoms, refs, swap!, reset!, deref). I argue that side-effects
should get special treatment as well -- with dedicated functions or protocols.

Using a single function (or "context" record) identifies effects in the same
way that `swap!` identifies state. Using a DSL provides a layer of abstraction
between _declaring_ what effect should happen, and _performing_ the actual
effect.

### Effect watchers (middleware)

Clojure synchronizes state mutations using a limited number of primitives,
making it simple to add a "watch" feature for mutable state: register "watch"
functions, and they'll be called any time the state changes. We can do
something similar using a single effects function: register functions to be
called any time a side effect happens.

There are innumerable uses for effects watchers and middleware. Here are a few:

* Instrumentation: e.g. using an open-tracing span for each effect

* Simple tracing and debugging: capture verbose execution logs, but only write
  them if an exception occurs.

* Capturing metrics: how many times was this effect called? How long did those
  effects take to run?

* Snapshot testing: record an execution, save it to a file, and replay the
  recording. The test checks that effects happen in the correct order, and that
  the input and output to each effect is the same as in the recording.

* Distributed coroutines? See [this article on defunctionalization](#defunctionalization)

### What about state libraries?

Libraries like [mount][mount], [component][component], and
[integrant][integrant] are useful for managing application lifecycle. These
libraries are great for _initializing_ global state, but they do not help
isolate effects that _use_ that global state.

However, we can treat the single `fx!` function as a piece of global state, and
use a state library to initialize it:

```clj
(require '[mount.core :as mount :refer [defstate]])

(defstate db-connection :start ...)
(defstate message-broker :start ...)

(defstate fx!
  :start (fn [k & args]
           (case k
             :write-to-db (sql/exec! db-connection ...)
             :send-updated-event (rmq/publish! message-broker ...))))

(comment
  (mount/start)
  (create-user fx! {:name "George"})
  )
```

## Further reading

### Background

#### Boundaries ![rb] <a id="reading-boundaries"></a> 

A talk by Gary Bernhardt about best practices around establishing boundaries.
Covers a lot of ground in a short amount of time: e.g. testing, mocks,
programming paradigms, and presents the functional core + imperative shell
model. Also makes connections between a number of other concepts such as
monads, hexagonal architecture, and the actor model.

<https://www.destroyallsoftware.com/talks/boundaries>

A more detailed dive into the Twitter client example from the above talk.

<https://www.destroyallsoftware.com/screencasts/catalog/functional-core-imperative-shell>


#### Ultratestable Coding Style ![clj]

A blog post about writing mini DSLs to separate the declaration of effects from
their implementations to make testing easier. Also a great example (with
diagrams) of how to take a function with complicated effects and untangle it.
Code ends up looking a lot like re-frame. The post contains links to example
code in Clojure.

<https://jessitron.com/2015/06/06/ultratestable-coding-style/>


#### Defunctionalization: Everybody Does It, Nobody Talks About It ![hs] <a id="defunctionalization"></a>

One way to think of defunctionalization is a process of turning ordinary
functional code into a DSL and an interpreter. This short article has some
excellent examples of defunctionalization, discusses some innovative things you
can do with defunctionalization.

For instance, taking a sequence of steps (e.g. effects) that must be done in
order, and distributing the computation across multiple machines:

> ... sending continuations over the wire in a different way: by sending over
> information about the execution’s past, so that the other machine may
> replay a function call in order to get to where the first machine left off.

<https://blog.sigplan.org/2019/12/30/defunctionalization-everybody-does-it-nobody-talks-about-it/>


#### Functors, Applicatives, And Monads In Pictures ![hs] ![clj]

Of all the introductory material on monads, this is one of the clearest and
simplest. The illustrations are a little goofy, but also quite helpful.

<http://adit.io/posts/2013-04-17-functors,_applicatives,_and_monads_in_pictures.html>

And a translation of the code into clojure

<https://fluokitten.uncomplicate.org/articles/functors_applicatives_monads_in_pictures.html>


### Free monads and DSLs

#### More freedom from side-effects ![fs]

A blog post with examples of creating a DSL using free monads. A simple
introduction to free monads.

<https://davesquared.net/2013/11/freedom-from-side-effects-fsharp.html>


#### Hexagonal Architecture and Free Monad: Two related design patterns? ![hs]

Part of a series on design patterns. The most relevant part is an example of
using free monads to create a DSL. One of the more involved examples, the code
linked from this post forms the basis for the train reservation example in this
repo.

<https://deque.blog/2017/07/06/hexagonal-architecture-a-less-declarative-free-monad/>

<https://github.com/QuentinDuval/HaskellTrainReservationKata>


#### Automatic Whitebox Testing Showcase ![hs]

A repo of example code and a long discussion about using free monads to write
DSLs that are recorded for later testing. The inspiration for the record/replay
mechanism discussed above and used in some of the example code.

<https://github.com/graninas/automatic-whitebox-testing-showcase>


### Effects systems

#### Effects for Less ![hs]

A talk by Alexis King at ZuriHac about a new Haskell effects system (eff) based
on delimited continuations. Very dense at times, but includes a great overview
of different approaches to effects systems (monad transformers, free monads,
and delimited continuations), as well as a tour of how GHC transforms,
simplifies, and optimizes code.

<https://www.youtube.com/watch?v=0jI-AlWEwYI>

<https://github.com/hasura/eff>


#### Building Haskell Programs with Fused Effects ![hs]

A talk by Patrick Thomson at Strange Loop about a new Haskell effects system
(fused-effects) and working to improve efficiency of effects systems. This is a
GitHub library that is used as part of their "jump to definition" feature
across languages.

<https://www.youtube.com/watch?v=vfDazZfxlNs&feature=youtu.be>

<https://github.com/fused-effects/fused-effects>


#### Algebraic Effects(-ish Things) in Clojure ![clj]

A discussion of algebraic effects, exceptions, and conditions. Perhaps the post
most similar to the discussion in this repo, but coming from a different angle
and with different examples.

<https://lilac.town/writing/effects-in-clojure/>

See also the related discussion on clojureverse

<https://clojureverse.org/t/algebraic-effects-ish-things-in-clojure/3687>




## License

Copyright © 2020 Democracy Works

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

[re-frame-async-flow-fx]: https://github.com/Day8/re-frame-async-flow-fx

[defunctionalization]: https://blog.sigplan.org/2019/12/30/defunctionalization-everybody-does-it-nobody-talks-about-it/




## Other notes

[mount]: https://github.com/tolitius/mount
[clojure-async]: https://github.com/clojure/core.async
[special]: https://github.com/clojureman/special
[component]: https://github.com/stuartsierra/component
[integrant]: https://github.com/weavejester/integrant

[promises]: https://en.wikipedia.org/wiki/Futures_and_promises
[async-await]: https://en.wikipedia.org/wiki/Async/await
[coroutines]: https://en.wikipedia.org/wiki/Coroutine
[generators]: https://en.wikipedia.org/wiki/Generator_(computer_programming)

[cognitect-aws]: https://github.com/cognitect-labs/aws-api
[clj]: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAAlwSFlzAAALEwAACxMBAJqcGAAAActpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IlhNUCBDb3JlIDUuNC4wIj4KICAgPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIKICAgICAgICAgICAgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIgogICAgICAgICAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyI+CiAgICAgICAgIDx4bXA6Q3JlYXRvclRvb2w+QWRvYmUgSW1hZ2VSZWFkeTwveG1wOkNyZWF0b3JUb29sPgogICAgICAgICA8dGlmZjpPcmllbnRhdGlvbj4xPC90aWZmOk9yaWVudGF0aW9uPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgPC9yZGY6UkRGPgo8L3g6eG1wbWV0YT4KKS7NPQAAAutJREFUOBFlU9tLlFEQ/327frubhql5xcoyIjSTVYh6s3DRh6IkiHoIfAmih96CIB8iAv+EIIigh4ICQRKlFQuCjLKLlzVvabpdvOW6u7q37zrNfCthNPA7M3POzJyZM3MUbBERuRVFsURluYFZgJEnOlOSMcDnw6JstxVdNnK2eA3LwYxu0c81opkfWYic1iw+oiCjZruPwhvOzcwDhmn3js5annvd83i/bJgTazZxdNSW5CitB705V84eQG2VS+cApzmbAcdXommaVuPxqCOzv2zP8ETMWFpPqXPLGdTt24EcNzD6LYPXCxrCOhkPL1aqbU2FEsTPQSbFX0qQ1CipJfW4viwiWSbRwqpN0z+JVqMmBd+u0vnbU1R4LaR/WciWI74uXdcbmbdMr47iev8h9eP8EH6kvmDFDCGRUPBo0EbPJzf8R0pwtbUIVR5FffB8HhndbuF7GlyqqjabSKNj0G/mKnU4utePvpW76Fm6ifKyOKoLXViKc5AhzrmmCE37vQjOaWZk0yUJBGQtCG/OoWsTdLzsDOLWIjatSUSNKe5dGFW7we9AiCQJ8ZQb9Qd8mPhtUiot/shzwhApjsZPzlxByhpDta8dRb7dqCg2+ZHcULImcLm2bMWUSQLE8ry5OOWDMhF5hwJ3BYo9rajLD2BkfQDlpVGU7uSrvIR8n4lXYwmcqFSVPeWOf1I60Jg2knSr/xK1PAX1T3VR1FykuLFCnTOgULyPlnmQfq0TDY3HqON+mGa/J6VRQjKx2TZORT/RsSfQ27uraWC6mza0CM0lPlA4GaKMuUFpg6EZTquzvhQUX6cg3pDxHOmdfuy58/myscsN1Z/fhvrik7DJQijyBgkjBre6Ylw43Kk27T+nG4bu93q9k1ICz5qTRYBlTTKRcpqfwcAj6AKRb7w4S+ORIbJtW2M7+Wh/fUX45zPJmyxmvtHX+LgDkWWP6f/PJJGE+HD7d5bpbGYUyBlTjPGSZ/+zKNtt/wDmcvSBg3l2IwAAAABJRU5ErkJggg== "Clojure"
[hs]: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAAlwSFlzAAADOwAAAzsBi8SqNgAAActpVFh0WE1MOmNvbS5hZG9iZS54bXAAAAAAADx4OnhtcG1ldGEgeG1sbnM6eD0iYWRvYmU6bnM6bWV0YS8iIHg6eG1wdGs9IlhNUCBDb3JlIDUuNC4wIj4KICAgPHJkZjpSREYgeG1sbnM6cmRmPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5LzAyLzIyLXJkZi1zeW50YXgtbnMjIj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiIKICAgICAgICAgICAgeG1sbnM6eG1wPSJodHRwOi8vbnMuYWRvYmUuY29tL3hhcC8xLjAvIgogICAgICAgICAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyI+CiAgICAgICAgIDx4bXA6Q3JlYXRvclRvb2w+d3d3Lmlua3NjYXBlLm9yZzwveG1wOkNyZWF0b3JUb29sPgogICAgICAgICA8dGlmZjpPcmllbnRhdGlvbj4xPC90aWZmOk9yaWVudGF0aW9uPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgPC9yZGY6UkRGPgo8L3g6eG1wbWV0YT4KGMtVWAAAAr5JREFUOBGlU11IVEEUnpk793d/7m7LIm1SoAU95INYgbrChmsRJKSwERRRVgauUfRiZLSXBHuoCKN68EnKDPTJLMwXkQKfInuxJyFMoXCRdP/u396Z5u5imBQ+dODAnJlzznzfd2YAAAACoKGys4jFWimmbP8/jYLtm+DjTRfCTpGGCIes4M7sIvhZtcvmCc8DnIGTMO2iYU7+hYWrqqyrohB+wBDfzK9xpqio+yQsvYUAna+pPjL1ZEH7kUiMcvNfRhlXiGIgBkuuxeDMzIzLH4B4fcd1gRcf2UWT9ULHVH9Fj08JNptmfvblm1SUpVA372/mNnCdtjRefC3ycqth5ud8SigpisqkIvtVu2jcfTF+O9Xf+mC/RFEWCzbUdQAYSqRL+jrSNK2EgkK727QKaVn01mZyK20cFq5R6lJHvcn2e20eSEe8krSMqLikiMJSwONdFAyU4hgPGotpePr9AJOvdpFQJ4Gx0LiWWRn2yGrWK6t1WVKsDjuwzyG2SSiZptSZLdjGPOP1js1/q5XpIs4dIUSmrQMPLx+cE1F9iDp3CASETTcNIE0jAAsco4CGhjQnHu3czc7GJcGjmGbufkV477qABa1IioBSCiROiK5y4GvIoWf9SuB0QFSbcnY++1vEeEPHhCQoJ5iIn3xqqFtEZREtW+/DvLjDJweTBUtfiDjFkxHbFAyCJcshSyUBWxo6bvBYfMgUpwjBo6o/0uOVA3HDyn8cmUgd6ko89ZpE/+z3hqvzVm5s8FXy1AZxFK/vrGFBr1OGesvnqzjAIRzXjcwqgbDTTXw2lsxRSLpy+prBI5y4embwsruvMfFh8+FzIczxQYfjrGg8sjw/m9uDMM/zDswOT/V/Z0+khJIJSi+1P67kFewREKcPPL/yzW2yjW0Uu2mb13+WsRs2f2cK3cn8rcD9nVvPfgEC9SPfBDq53QAAAABJRU5ErkJggg== "Haskell"
[rb]: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAAACXBIWXMAAAsTAAALEwEAmpwYAAABWWlUWHRYTUw6Y29tLmFkb2JlLnhtcAAAAAAAPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIgeDp4bXB0az0iWE1QIENvcmUgNS40LjAiPgogICA8cmRmOlJERiB4bWxuczpyZGY9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkvMDIvMjItcmRmLXN5bnRheC1ucyMiPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIgogICAgICAgICAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyI+CiAgICAgICAgIDx0aWZmOk9yaWVudGF0aW9uPjE8L3RpZmY6T3JpZW50YXRpb24+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICA8L3JkZjpSREY+CjwveDp4bXBtZXRhPgpMwidZAAADd0lEQVQ4EV1TW2wUVRj+zpkzs7fpUnvZXZruQkGReEFFDUZNTOTBB2PUkDTxARPejPGRxBdv4JM2wSgmYCKNeCGlD9aUJoJYq0RDEwPhoqilyN4ou93LzO50d2dm55zj2Rpf/J/m/Of/vvn+7/8Pwf9CApQAopduzZ/e6RcKe/vu37G9/fu19srL+2gjPULy9iova2z2Nds9rmr/DSklOUAIeUeBZenW88tTJ163Zr95zLRqRLf+wthn3+OPH37E0sF34Y4mYK85uEXlW+sEPXAvFJUonvr6ldJ3Z4/UDx9BoBLGXZu4vJ7DA3OzMFJJ8e0ju1CKR6Q0dMPxvOp/CmgPvPzFp/tzk8cmqgvnEUuYgcZ9pqkLZvYhM/4SvKtX0KnX5LXl62LJJ1rQ7bboNNCrEZWf5vfnpk9OlBbOy3BmWEh3jUnzDrRqPqLPPAfH7eLv0+dQKRRJgul0VATwuHDoOMCdi4v3LE4eez936izC6QHJ7QqVsTi6uTJEGBjduw/ZG3k46k9VRVSwW6CcIw1YPelY+Grq7cLxE0RLxIKgWacy3o/Ad2Gru4emZzCYTMrG1UvSVeeuAioiWVXjinKo4mz2ab5aGb+tkq6kLEhsQqdoo6GkP/jeQZi5m6h9NEEGNibXFXQUgcLKuBp0VKLKPCLHt0mOrGql7nNWU46nnnoCD7/4AsjFC1j6fArGfVshmg48paATcHQUQUYRdFULDE1bpHfvxo4vp/Cn08booUO4d/s2rHz4AZpn5qFtHgFKZSjHwQ3A5xJaRIOh1o34pMZW9jwZG5tZxKMXfsVO34eZSuHMmwdwQ4GHt2RAS0VomrJKCCKUdmJoYDpBhIbQpxtlll9qma3DHyPz+C7CiwXkLl/C6skZRFNDsHJ5dNWQKRcypMCMEaKFKDg4YYaOBI1bbDWdjFePfgJtbpKEBzO43VJm6HFIuwrC1LcC1gRInGjYykyU4cq86BKplAxHTJt5a45Hh4aQ1aJ8sC1pud6Rqjn4ErIjIJVRGGMxmQnFEDUMbNYHiaE2uBw4MCNbLMYFZnTpPVu0Xd3zKQKhE50EvVERrhZ5oxHBiB5BVDdg6iFsMMLYM3A3fgua+Nkr2+tv4Wh/9I01Il8daut9IbC2w5qNgFEbGrM4hNUBb1Ap67qmVTZo4cZY/7BzZ3/Smgtu/vIP4y2uUHs965IAAAAASUVORK5CYII= "Ruby"
[fs]: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAMAAAAoLQ9TAAAABGdBTUEAALGPC/xhBQAAACBjSFJNAAB6JgAAgIQAAPoAAACA6AAAdTAAAOpgAAA6mAAAF3CculE8AAABX1BMVEUAAAA3i7owuds3iLgwuNsvt9o3irk4hrcwuts5gbN1AAA2i7k1l8MplcQyt9w4hLQwudo3h7cq4/g3jLs6d6s1l8Mwttgwu90wuds3i7o3iLg2jrwvvd4xtNc3jLo3i7o3irovvd4wuNowuds3i7o3i7o3i7ovvd4wudswudswuds3i7o4h7cvvd4wudswuds3i7o3ibkwutwwudswudswuds3i7o3i7o3i7o3i7o3i7ozps4wudswuds3i7o3i7o3i7o3i7o3i7o0nscwudswudswuds2i7k3i7o3i7o3i7o3i7owudswudswuNs2i7k3i7o3i7o3i7o3i7owtdgwudswuds3i7o5i7w3i7o3i7o5e64wutwwudswuds3ibkvvd4wudswuds3i7o3i7ovvd4wudsymMA3i7o3i7ovvd4wuNo4hbU2kL0wvN0zqM83i7o3i7owuNswudv///+rTK7GAAAAcXRSTlMAAAAAAAAAAAAAAAAAAAAAAAAAAgEGBAECAy2EgRMc7aTNywkg2Z/Ewg8D3F+ZyhN+IgW00Bce3Wx6qgjWGhzgc2acBrbfHAy3oFCi07UMBbSaV5gj3KoCAbOsEB/dooKzmwGwnsOSAsSpzJoTYVgDAQl8ha4AAAABYktHRHTfbahtAAAACXBIWXMAAA7DAAAOwwHHb6hkAAAA40lEQVQY0yXPd1MCQQyG8WxQQVEB61mxYTkr9q6IYqEJFuyFYgfN5fvPmN378/nNOztZAFBKtXd0dnVbiAhgusfT29c/gCJ1bqvB8NDwyGi92egei9D4hDM5hbYG6WmimVmH5+ZFwO2F6OIS8/IK2qBW14jWNzYbtkS2dxB294j2Y3TgjfsOmY8ScHxCdHpGycamRIo5nQF/9pwol79ovrxivpZHCzfZW6K7+4dH5qcWywLlyvOLw69oFfVhIiUqV5w3tItymF/L+8fn17fpVjAS+KnWfmWPQf1dkb9CqM3sAf4BSAkydo2yBi8AAAAldEVYdGRhdGU6Y3JlYXRlADIwMTQtMTAtMDJUMDA6MzY6NDUrMDI6MDAsmC16AAAAJXRFWHRkYXRlOm1vZGlmeQAyMDE0LTEwLTAyVDAwOjM2OjQ1KzAyOjAwXcWVxgAAAABJRU5ErkJggg== "F#"
