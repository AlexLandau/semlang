These notes were last updated in October 2017.

### What is this language meant to make possible that other languages do not?

At least one of the following use cases, the more the better (forgive the non-parallel explanations):

1. I am writing a library that performs pure data manipulation. I want to be able to write it in one language and
transpile it to many languages/platforms, with guaranteed consistent behavior across those platforms. I shouldn't have
to be familiar with the target languages to write or test my code, though requiring some fine-tuning to maximize
performance is acceptable (and should be practical to accomplish).
2. We currently have JSON and XML (and others) as interchange formats for data with nearly universal support across
languages and platforms. I can write an API for sending data between servers and clients that uses these formats and not
worry about the ability of future programs to run against that API. However, if I want to send a function -- a
well-defined transformation of data -- across the API, there is no such lingua franca. In addition, there are immediate
security concerns to running client-defined code on a server.
3. A significant amount of software engineers' time is spent optimizing and tuning performance by making certain types
of adjustments to the code that are, in a sense, routine. Examples are batching read calls from a database; rewriting
critical sections of the code to avoid repeated computations; and adjusting certain parameters that exist for the sole
purpose of tweaking performance. We should be unleashing the power of software itself to make these changes. The problem
is not that software is bad at these types of optimizations; it is that we use languages that give software insufficient
understanding of what we are trying to achieve. In most cases, a compiler cannot tell which side effects of a function
are its ends and which are its means.

We hope to also support one more use that may be fulfilled by some existing languages that the author is not yet
familiar with:

1. It should be easy to add expected properties or invariants of parts of the code without learning an entirely foreign
syntax, and to have proofs of these invariants checked as part of the build system. Ideally, proofs would be constructed
automatically by a theorem prover when possible (or present counterexamples if they are discovered), and in more complex
cases would be built through a tool allowing collaboration between the programmer and the prover.

### What properties of the language are needed to fulfill these use cases?

* Minimality: The concept for this language was in large part inspired by GDL (the Game Description Language), a highly
minimal Prolog-like language. Its minimality made extreme transformations to the language possible, such as transforming
it to a graph of logic gates which could then be processed more rapidly. It was easy to write these because there were
so few cases in the language that needed to be handled. Accordingly, this language should have the minimal number of
features necessary to support expressive programming patterns. (This will inevitably be a judgment-based tradeoff.)
* Determinism 
* Strong static typing: The author believes that static typing is necessary for the best possible developer experience.
  Combined with a high degree of determinism and a lack of side effects, it will also make it possible to verify that a
  function may be run safely by type-checking it and then checking its type signature.
* Usable: In order to achieve any level of success, this language must be highly usable by developers. This means:
  * The language's tooling should meet and exceed the expectations set by modern programming languages.
  * The tooling should also be highly supportive of "dialect" languages that transpile to the language. This will allow
    the development of new features for a language from a developer's perspective without sacrificing the language's
    minimalism.
  * The base language should be sufficiently similar to existing popular languages that it will be easy to read and
    interpret.

### What are the current principles and invariants of the language's current design?

1. The code defines what the environment should do, not how it should do it. For example, there is no preference for
   eager or lazy evaluation: this is a concern of the environment, not the language itself. There is also no distinction
   between code that should run single-threaded and code that should run in parallel. The environment running the code
   is thus given maximal flexibility to determine the most efficient or desirable way to accomplish its intended effects.
2. There are only two permitted sources of non-determinism:
   1. The program may crash and fail at any time, generally due to lack of resources or hardware failures.
   2. Monads or an equivalent construct (which are not yet in the language but expected eventually).
3. There is no global state. The only things in the global scope are the top-level entities: functions, structs, and
   interfaces. Access to necessarily global state should be managed through monads that are passed to the "application
   function" as arguments.

Other assorted notes:

* It is expected that there will be more than one version of the language with differing feature sets. The idea is to
  have a language that is bearable to code in directly, and which is good as a source for transpiling into legible code
  in other languages, as well as a language that is more minimal and easier to run transformations on (especially whole-
  program optimizations).
* Subtyping: Currently there is no subtyping. This may change in the future, but I'd prefer to avoid it if I can find
  other ways to offer the same benefits. Type verification of generics is far simpler and less error-prone with exact
  types only.
* Annotations: These are far from their intended final form. Expect these to change. One guiding principle, however, is
  that annotations shouldn't change the meaning of code in a way that contradicts what it would mean in the absence of
  the annotation.
* Modules: There is a prototypical module system in place; the tooling around this is still under construction.
