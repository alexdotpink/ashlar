# Command and event showcase plug-in

This Paper/Folia plug-in is a playable reference for the complete command and event modules. The command catalogue root is `/showcase`, with `/sc` as the short alias and `/demo` as a best-effort alias. The event catalogue root is `/events`, with `/ev` as its alias.

Start with `/showcase` for clickable links, `/sc guide` for a short path, or `/sc help [page]` for generated, permission-filtered help. Tab completion is part of the demonstration; try it after every literal and for the landmark argument.

## Event checklist

Join the server and wait half a second. The static `@On` handler records the join and the suspending `@Observe` handler sends a clickable `/events` prompt. Then run:

```text
/events state
/events publish hello
/events custom hello
/events state
```

The state includes ordinary and suspending application handlers plus the custom Bukkit event. It also includes `lifecycle:commands`, registered through Paper's native lifecycle key. The excluded event set never adds `excluded`.

Try one-off cancellable capture:

```text
/events choose @s
```

Send `maybe` in chat; the capture consumes it and asks for `yes` or `no`. Send `yes` to complete the command. Then try a bounded Flow:

```text
/events collect @s 2
```

Send two chat messages. The command replies with both projected strings. Disconnecting records the quit through a component-owned dynamic listener; reconnect and run `/events state` to see it.

## Playable checklist

### Structure and parsing

```text
/sc
/sc basics basic-hello Alex
/sc basics primitive-types 1 2 3.5 4.5 true Kotlin
/sc basics greedy-text a decoded message with spaces
/sc basics repeated-words alpha beta "two words"
/sc modern-name
/sc mn
/sc old-name
```

These cover root handlers, inferred names, aliases, a temporary renamed route, Kotlin defaults, built-in codecs, greedy text, repeated values, nested groups, and a `Unit` response.

### Custom arguments and observer metadata

```text
/sc arguments argument-landmark <tab>
/sc arguments argument-landmark lighthouse
/sc arguments qualified-words MixedCase MIXEDcase
/sc arguments observed-secret public-label do-not-log-this
/sc arguments sensitive-link
/sc diagnostics
```

The landmark uses a custom codec plus a separate suggestion provider. The qualified words use two codecs for the same Kotlin type. Diagnostics shows the observed label while the sensitive value never appears in observer metadata; the clickable sensitive link is built by a generated typed route.

### Named options

```text
/sc options direct-options --count 3 --verbose --note hello
/sc options direct-options -c 3 -v --note=hello
/sc options option-bundle market square --limit 3 --verbose --tag red --tag blue
/sc options option-bundle --no-exact market square
```

These cover direct options, short names, `--name=value`, booleans and negation, nullability, `OptionValue` presence, reusable `@Options` defaults, repeated options, and options interleaved with greedy positional text.

### Responses and failures

```text
/sc responses response-string
/sc responses response-component
/sc responses response-multi
/sc responses response-custom
/sc responses response-reject
/sc responses response-exception
```

The handlers return `String`, Adventure `Component`, multi-message `CommandResult`, a domain type encoded by a contributed response codec, an expected stackless rejection, and a domain exception handled by its most-specific contributed handler. Invalid input also demonstrates the contributed message catalogue.

### Policies and coroutine lifecycle

```text
/sc policies policy-cooldown
/sc policies policy-rate-limit
/sc policies policy-sliding-rate
/sc policies policy-single-flight
/sc policies policy-confirm danger
/sc policies policy-custom
/sc policies policy-retire 20
```

Run cooldown or rate-limit commands repeatedly. Run the confirmation command twice exactly. Run single-flight twice before its two-second suspension finishes. To test executor retirement, run `policy-retire 20`, disconnect, then reconnect: its response is cancelled with the retired executor.

### Routes and graph composition

```text
/sc graph redirect-source
/sc graph fork-source
/sc graph external-source
/sc graph optional-source
/sc typed-dispatch
```

These exercise a typed redirect, a supervised two-target fork, a required external edge to Paper's `version` command, removal of an unavailable optional edge, and direct `CommandDispatcher` submission. The response and policy sections come from separately compiled `@CommandFragment` classes. The unregistered `/excluded-showcase` root proves plug-in-level contribution exclusion.

### Scopes, permissions, and refresh

```text
/sc who-am-i
/sc dynamic-permission
/sc player @s player-identity
/sc player @s refresh-tree
/sc admin restricted-route
```

These cover an invocation scope with injected `CommandInvocation`, a dynamic permission check, a group constructor with a shared native player argument, targeted command-tree refresh, and an inherited group permission. The development server grants the test player operator permissions.

### Native Minecraft arguments

Every value in `commands.minecraft` has a route under `/sc minecraft`:

```text
/sc minecraft selectors native-player @s
/sc minecraft selectors native-players @a
/sc minecraft selectors native-entity @s
/sc minecraft selectors native-entities @e[limit=3]
/sc minecraft selectors native-profiles @a

/sc minecraft positions native-block-position ~ ~ ~
/sc minecraft positions native-block-column ~ ~
/sc minecraft positions native-fine-position ~ ~ ~
/sc minecraft positions native-fine-column ~ ~
/sc minecraft positions native-rotation ~ ~
/sc minecraft positions native-angle ~
/sc minecraft positions native-axes xyz

/sc minecraft blocks-items native-block-state ~ ~ ~ minecraft:stone
/sc minecraft blocks-items native-block-predicate ~ ~ ~ minecraft:stone
/sc minecraft blocks-items native-item minecraft:diamond
/sc minecraft blocks-items native-item-predicate @s *

/sc minecraft text native-named-color red
/sc minecraft text native-hex-color 55ff99
/sc minecraft text native-component {"text":"hello"}
/sc minecraft text native-style {"color":"gold","bold":true}
/sc minecraft text native-signed-message hello

/sc minecraft values native-display-slot sidebar
/sc minecraft values native-namespaced-key minecraft:stone
/sc minecraft values native-adventure-key minecraft:stone
/sc minecraft values native-integer-range 1..10
/sc minecraft values native-double-range ..5.5
/sc minecraft values native-world minecraft:overworld
/sc minecraft values native-game-mode creative
/sc minecraft values native-height-map world_surface
/sc minecraft values native-uuid 1ed2dfa7-07df-4d36-8bc0-436b500bc3f2
/sc minecraft values native-criterion dummy
/sc minecraft values native-look-anchor eyes
/sc minecraft values native-time 20t
/sc minecraft values native-mirror none
/sc minecraft values native-structure-rotation none

/sc minecraft registries native-registry-value minecraft:diamond
/sc minecraft registries native-registry-key minecraft:diamond
```

Selectors and server-owned objects are converted to stable references or snapshots before coroutine execution. The block-state and predicate commands then demonstrate explicit region-owned access.

## Build and run

```bash
./gradlew :sample-plugin:shadowJar
./gradlew :sample-plugin:runSamplePaper
./gradlew :sample-plugin:runSampleFolia
```

The shaded JAR is written to `samples/sample-plugin/build/libs`. It includes the framework, Kotlin, and coroutines, but not Paper itself.
