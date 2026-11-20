# Working with Flow as an AI agent

Flow (`io.github.aedev.flow`) is an Android music/video app written in Kotlin with Jetpack Compose, Hilt, and Media3/ExoPlayer. It plays YouTube content via a native InnerTube client with a NewPipe-based fallback extraction path, supports local media playback, offline downloads, casting, lyrics, a device-to-device sync feature, and an on-device recommendation engine (FlowNeuroEngine). It follows Material 3 design guidelines closely.

Product flavors: `github` (default, in-app updater enabled) and `foss` (no updater). Always use flavor-prefixed Gradle tasks — e.g. `assembleGithubDebug`, `compileFossDebugKotlin` — never bare `assembleDebug`/`compileDebugKotlin`.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Material 3 — strict guidelines

1. All UI is built with Jetpack Compose Material 3 (`androidx.compose.material3`). Never introduce Material 2 (`androidx.compose.material.*`) components into new or edited code.
2. Use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, and `MaterialTheme.shapes` tokens exclusively for color, type, and shape. Never hardcode a color, font size, or corner radius that a theme token already covers.
3. Before implementing or fixing any Compose/Material 3 component, consult the official documentation (developer.android.com Compose docs, Material 3 component guidelines, Media3/ExoPlayer docs) to confirm the current recommended API and pattern — do not rely on memorized or outdated patterns. Training data lags the framework; verify against current docs when in doubt.
4. Respect Material 3 motion, elevation, and state-layer specs as documented — do not invent custom equivalents when a Material 3 component already provides them correctly.

## The "Anti-Slop" Manifesto (zero tolerance)

Models default to dated "AI slop" UI trends. The following are STRICTLY FORBIDDEN anywhere in this codebase:

- ❌ **No gradients** — do not use `Brush.linearGradient`/`verticalGradient`/etc. as a background or surface fill unless explicitly instructed, and never as a substitute for a real Material 3 surface color.
- ❌ **No glassmorphism** — no blurred, frosted, or semi-transparent "milky" backgrounds (`Modifier.blur` on backgrounds, translucent overlay panels) outside of the one existing, deliberately-designed ambient/blur surfaces already in the player (do not add new ones elsewhere).
- ❌ **No fake drop shadows / glow effects** — do not hand-roll `shadow()` glows or neon box-shadow equivalents. Use Material 3's built-in elevation/tonal-elevation system only.
- ❌ **No colored borders around cards** — card/container borders must be neutral (`MaterialTheme.colorScheme.outline`/`outlineVariant`), never a primary/accent-colored stroke used as decoration.
- ❌ **No arbitrary hex codes** — never write `Color(0xFF...)` inline. All colors must come from `MaterialTheme.colorScheme` (or the app's defined color scheme source). If a needed color doesn't exist yet, add it to the theme definition, don't inline it at the call site.

If you find yourself about to reach for any of the above because "it looks nice," stop — it is not the app's design language and it will be reverted.

## Use the platform — never hand-roll an API a dependency already provides

**The default is always the library implementation.** Hand-rolling is a fallback that must be
*earned* by verification, never a first move. A hand-rolled equivalent of a shipped API is
strictly worse: it misses accessibility, RTL, theming, state restoration, edge cases and security
fixes that the real one already handles, it will not track upstream behaviour changes, and it
becomes code the project has to maintain forever.

This rule applies to Material 3 and M3 Expressive components, Compose foundation/animation/
gesture APIs, Media3/ExoPlayer, AndroidX (WorkManager, Paging, Room, DataStore, Lifecycle, Glance),
the platform SDK, and every third-party library in `gradle/libs.versions.toml`. It applies equally
to protocols and formats — do not write a parser, a codec, a cipher, a signature scheme or a
transport by hand when a maintained implementation is on the classpath.

### The procedure — verify, then decide

Before writing any component, effect, animation, formatter, parser, scheduler, or player behaviour:

1. **Check what the app already has.** `graphify query "<thing>"`, then read
   `ui/components/shared/` and `utils/`. The commonest waste is re-implementing something this
   codebase already exports.
2. **Check the version catalog.** `gradle/libs.versions.toml` is the list of what is available.
   Read it before assuming something must be built.
3. **Check the actual artifact, not your memory.** Training data lags these libraries badly, and
   the app is on alpha Compose/M3. Confirm against the bytecode:

   ```bash
   # locate the artifact (group/artifact/version are directories)
   find ~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3 -name '*.aar'

   # does the API exist at all?
   unzip -p <path>/material3.aar classes.jar > /tmp/c.jar
   unzip -l /tmp/c.jar | grep -i carousel

   # what is its exact signature?
   javap -classpath /tmp/c.jar androidx.compose.material3.MotionScheme
   ```

   Several versions of a library can sit in the cache at once. Confirm which one actually resolves
   with `./gradlew :app:dependencies` before trusting what you found.
4. **Check the official docs** for the recommended pattern (developer.android.com Compose docs,
   Material 3 component guidelines, Media3 docs). An API existing is not the same as it being the
   right one.
5. **Only if steps 1–4 come back empty may you hand-roll it** — and you must say so explicitly in
   the PR description, naming what you checked and what you found missing.

### The current stack — reach for these first

| Need | Use | Do not hand-roll |
| --- | --- | --- |
| Any UI component | `androidx.compose.material3` 1.5.0-alpha26 (M3 Expressive) | Custom buttons, chips, sheets, FABs, carousels, progress indicators, search fields |
| Motion | `MaterialTheme.motionScheme` specs | Bespoke `tween`/`spring` constants at call sites |
| Shapes | `MaterialTheme.shapes`, `androidx.graphics.shapes` | Hand-drawn paths for standard shapes |
| Haptics | `HapticFeedbackType` constants | `Vibrator` calls with hardcoded durations |
| Pull to refresh | M3 `PullToRefreshBox` (already in use on 5 surfaces) | Custom drag-to-refresh |
| Reordering | `sh.calvin.reorderable` (already used in `QueueSheet`, `PlaylistPage`) | Custom drag-and-drop |
| Paging | Paging 3 (`androidx.paging`, already used by search and channel) | New hand-rolled page cursors |
| Playback | Media3 1.11.0 — exoplayer, hls, dash, session, datasource-okhttp | Custom player wiring, manifest handling, or media notifications |
| Background work | WorkManager 2.11.2 | Custom threads, timers, alarms, or wakelocks |
| Persistence | Room 2.8.4, DataStore Preferences 1.1.7 | Hand-rolled file or SharedPreferences layers |
| JSON | `kotlinx.serialization` (103 files) | Gson for new code — it is kept only for legacy DTOs and carries an R8 reflection hazard |
| HTTP | OkHttp 5.4.0 / Ktor 3.5.2 | Raw sockets or `HttpURLConnection` |
| Dates and times | `java.time` — **core library desugaring is enabled, minSdk 26**, so all of it is available | `SimpleDateFormat`, `Calendar`, or manual millisecond arithmetic |
| Images | Coil 3.5.0 | Custom loaders, caches, or decoders |
| Widgets | Glance 1.1.1 + `glance-material3` | Hand-built `RemoteViews` |

Known debt in this table, to fix opportunistically when already in a file: 13 files still use
`SimpleDateFormat` and 8 use `Calendar.getInstance()` against only 4 on `java.time`.
`re2j` is declared in `app/build.gradle.kts` but referenced from no first-party source — verify
whether it is a transitive requirement before either using or removing it.

### When hand-rolling is legitimate

Only when the API is **verifiably absent or verifiably unfit**, and the reason is recorded. The
reference case is `ui/components/shared/FastScrollbar.kt`: Compose Foundation genuinely ships no
scrollbar API — `unzip -l` over its `classes.jar` returns zero matches for `scrollbar` — so a
custom one was justified. Note what it still does: it is built *on* the platform
(`draggable`, `LazyListState.requestScrollToItem`, `MotionScheme` specs, `HapticFeedbackType`), it
does not reimplement any of them.

A legitimate hand-rolled component must therefore:

- be built from the framework primitives, not around them;
- take theme tokens for every colour, shape, type and motion value;
- live in `ui/components/shared/` if more than one feature uses it;
- carry no duplicate of behaviour the library already provides.

### Never

- **Never fork, vendor, or copy-paste a library's source** into the app to change one thing.
  Configure it, wrap it, or file the constraint — do not clone it.
- **Never add a new dependency that overlaps one already on the list.** Use what is there. A new
  dependency needs a stated reason that an existing one cannot cover.
- **Never hand-roll security-relevant code** — crypto, signing, token handling, TLS. The app has
  BouncyCastle, OkHttp and the platform providers.
- **Never re-invent player wiring.** See the performance rules below: Media3 owns playback, the
  media session, and the media notification.
- **Never disable, wrap, or work around a library API because its behaviour surprised you** until
  you have confirmed the behaviour against its documentation. The surprise is usually a misuse.

## Performance, battery, and thermals — non-negotiable

Flow is a media player that runs for hours at a time. Jank, dropped frames, playback stutter,
device heat, and battery drain are critical bugs, not cosmetic issues. Every rule below is
anchored in a real shipped regression that had to be found and fixed on-device — treat them as
hard constraints, not suggestions.

### Frame discipline — nothing animates that the user cannot see

1. **The invisible-animation rule.** Several player surfaces deliberately stay composed while
   hidden (the full player sheet is kept warm behind the mini player; the lyrics panel is
   retained after first open; the mini bar stays composed under the expanded player). Anything
   animating inside a hidden layer burns a full frame budget at 60–120 Hz for entire listening
   sessions — this exact pattern caused a 30%-battery-in-90-minutes overheating regression.
   EVERY continuous animation MUST be gated on its own layer's visibility and pause when the
   layer is hidden. Gate on anchor state (e.g. `state.isExpanded`) or a `derivedStateOf` over
   the fraction — never remove the warm composition itself (it exists to make first-expand
   jank-free), and never read a raw animated fraction in composition (see rule 3).
2. **Audit list for continuous work.** When adding or reviewing UI, search the affected tree for:
   `withFrameMillis`/`withFrameNanos` loops, `rememberInfiniteTransition`, `basicMarquee`,
   `animate*AsState` whose target changes on a timer (a 1 Hz-retargeted tween is a continuous
   animation), wavy/squiggly/indeterminate indicators, and polling `LaunchedEffect`s with short
   `delay`s. Each one needs an explicit answer to: "what stops this when its layer is hidden,
   when playback is paused, and when the screen is off?"
3. **Per-frame values are read in the layout/draw phase, never in composition.** The player
   sheets pass animated values as lambdas consumed inside `Modifier.layout`/`graphicsLayer`/
   `drawBehind` so dragging never recomposes the tree. Preserve that contract: a raw
   `animatable.value` or expansion-fraction read in composition recomposes every frame of every
   gesture. For booleans derived from a fraction, use `derivedStateOf` so recomposition happens
   only on the flip.
4. **Expensive draw effects.** Full-screen `blur`/`RenderEffect` layers must hold static content
   (invalidated only on track/state change, as `PlayerBackground` does). Never attach a blur or
   render effect to a node that invalidates per frame, and never add new blur surfaces (see
   Anti-Slop).

### Cadence contracts — polling and updates

5. **Position cadence is a contract.** `EnhancedMusicPlayerManager` emits `playerState` at 1 Hz
   (whole-second coarsening) and a precise 250 ms tick ONLY while a refcounted consumer
   (`acquirePreciseProgress`/`releasePreciseProgress`, tied to the expanded sheet) is present.
   Never widen these, never add a new high-frequency position StateFlow, and never collect the
   precise tick from a surface that renders whole seconds. New sub-second consumers must use the
   same refcounted acquire/release pattern with a `DisposableEffect`.
6. **Event-driven over polling.** Prefer player callbacks, Flow emissions, and
   `snapshotFlow`/`collect` chains to timer loops. Any unavoidable polling loop must suspend
   while paused (await a state change, as the position loop does), never busy-wait, and its
   interval must be justified against what actually changes at that rate.
7. **Battery/background behavior is part of every change**: dynamic `wakeMode` (LOCAL foreground
   / NETWORK background audio) stays as is; no new wakelocks; no work keyed to the frame clock
   while the screen is off; WorkManager/jobs must tolerate App Standby buckets.

### Work economy — network, CPU, and ViewModel scope

8. **One fetch per cause.** Every network call must be traceable to exactly one triggering event,
   deduped by id where re-triggering is possible (the per-track related fetch was silently
   duplicated by a warm tree re-firing an effect the ViewModel already handled — that class of
   bug is a regression, not a nit). Effects in warm-kept trees fire on every key change even
   while invisible: gate their network side effects on visibility or dedupe in the ViewModel.
9. **ViewModel scoping is a performance decision.** A bare `hiltViewModel<MusicViewModel>()` at a
   navigation route creates a fresh backStackEntry-scoped instance whose `init` re-runs the full
   home-load pipeline (~60 network calls) on every page open — this caused the 30-second Daily
   Mix loads and device heat. Shared surfaces use the activity-scoped `sharedMusicViewModel()`;
   never reintroduce per-route instances of ViewModels with expensive `init`. New ViewModels must
   not fire network floods from `init` at all — load lazily, cache, and refresh on staleness.
10. **The shared pools are finite.** `PerformanceDispatcher.networkIO` is a fixed 4–16 thread
    pool. Launch parallel work as one bounded round (`map { async { … } }.awaitAll()`), never as
    an unbounded per-item fan-out, and never queue a second copy of a pipeline that is already
    in flight.
11. **Flow lifecycle**: `stateIn(WhileSubscribed(5_000))` on UI-facing flows is load-bearing —
    subscription count gates work to actual UI visibility. Never switch to `Eagerly`/`Lazily`
    for convenience, and never add hot collectors that outlive their surface.
12. **Caches must be honored and invalidated.** Check for an existing cache (music home cache,
    related-lane caches, Coil) before adding a fetch; new caches need explicit invalidation
    (TTL, region change, refresh) and must be read through, not around.

### Overheating triage — when heat or drain is reported

13. Sustained heat while the app is open = per-frame work; drain with the screen off = CPU/network
    loops. Diagnose in that order: (a) run the rule-2 audit over every composed-but-hidden tree;
    (b) count fetches per user action in logcat — any unexplained second fetch is the bug;
    (c) check `adb shell dumpsys gfxinfo io.github.aedev.flow` for continuous frame production
    while the UI should be idle; (d) only then suspect the player path. Do not "fix" heat by
    degrading visible design, motion, or update smoothness — find the invisible work instead.

### Proof — no performance claims without evidence

14. Never describe a change as faster, lighter, or cooler based on reasoning alone. State exactly
    what was measured or verified (frame counts, load times from logs, fetch counts, benchmark
    output) and what was not. Recommendation-engine changes require their offline benchmarks
    (`MusicBenchmarkTest` / `NeuroBenchmarkTest` regression floors) before and after; startup
    changes require `StartupBenchmark`, not baseline-profile size.
15. Player-path changes (ExoPlayer/Media3 setup, buffering config, surface handling, track
    selection) must not introduce added latency, buffering stalls, or black-screen/flicker
    regressions. Follow the existing player architecture rather than re-inventing player wiring.
16. The Compose basics still apply everywhere: hoist state, `remember`/`derivedStateOf`/
    `rememberSaveable` used correctly, stable/immutable composable parameters, lazy containers
    with stable `key`s for any large collection, no blocking I/O or DB/network on the main
    thread, structured concurrency with correct dispatchers and cancellation. When in doubt,
    check the official Compose performance docs and Media3 best practices before shipping.

## Dependency injection and service-locator migration

Flow uses Hilt, but some legacy app-owned classes are still reached through static/companion
`getInstance()` calls. Treat those calls as migration debt, not as the pattern for new code. The
goal is explicit, testable dependencies while preserving object identity, lifecycle, startup cost,
and playback behavior.

1. Use constructor injection by default for new or migrated app-owned ViewModels, repositories,
   use cases, workers, services, and managers. A Hilt-managed `@Singleton` is valid when the object
   truly has application-wide identity; the problem is hidden global access, not singleton scope
   itself.
2. Do not add new app-owned `getInstance()` calls. This rule does not apply to normal platform or
   library factory APIs such as `Calendar.getInstance()`, `MessageDigest.getInstance()`,
   `WorkManager.getInstance()`, or `ProcessCameraProvider.getInstance()`.
3. Migrate incrementally when a class is already in scope. Do not perform a repository-wide DI
   rewrite as incidental cleanup. Keep each migration small, reviewable, independently testable,
   and easy to revert.
4. Before changing construction, use `graphify query`/`graphify path` and code search to enumerate
   every caller, Hilt binding, lifecycle owner, entry point, and flavor-specific implementation.
   Record whether the current instance is lazy or eager, when it is initialized/released, and
   whether callers rely on reference identity or shared mutable state.
5. Preserve lifecycle and cardinality exactly. A migration must not create a second database,
   repository, cache, coroutine scope, network client, player, media session, or background
   service. Match the narrowest correct Hilt scope (`@Singleton`, `@ActivityRetainedScoped`,
   `@ViewModelScoped`, or unscoped) and use `@ApplicationContext`/`@ActivityContext` explicitly.
6. Keep constructors and Hilt provider methods free of blocking I/O, network/database work,
   player preparation, and unrelated side effects. Start lifecycle work in the existing structured
   coroutine/lifecycle boundary. If moving work from an explicit `initialize()` method to `init`,
   verify that creation timing, cancellation, retry behavior, and error handling remain equivalent.
7. Prefer `@Inject` constructors for classes the app owns. Use `@Binds` for meaningful interface
   mappings and `@Provides` for third-party types, private constructors, configuration-dependent
   factories, or temporary adapters around legacy singletons. Do not create an interface for every
   class solely to claim SOLID compliance; introduce an abstraction when it represents a real
   boundary or enables a useful fake/alternate implementation.
8. ViewModels use `@HiltViewModel` plus constructor injection and are obtained from Compose with
   `hiltViewModel()` using the intended `ViewModelStoreOwner`. Composables should receive state and
   callbacks or a ViewModel; do not turn composables into service locators. Use supported Hilt
   integrations for workers/services, and keep any Hilt entry point confined to an Android boundary
   that Hilt cannot construct directly.
9. Remove a legacy `getInstance()` API only after all app-owned callers have migrated and tests
   prove the replacement preserves the same instance semantics. Transitional Hilt providers may
   delegate to the legacy singleton, but consumers must inject the dependency so the global access
   is isolated and can later be removed.
10. Player-path migration is high risk. Do not migrate `EnhancedPlayerManager`,
    `EnhancedMusicPlayerManager`, `ShortsPlayerPool`, player services/media sessions, surfaces,
    caches, or their app-start initialization as opportunistic cleanup. It requires an explicit
    task, a dedicated architecture plan, and end-to-end verification of audio/video playback,
    background playback, queue continuity, configuration changes, process recreation, PiP,
    casting, local media, error recovery, and release behavior. Preserve exactly one intended
    player/media-session owner and do not add startup latency or surface flicker.
11. DI and SOLID are maintainability/testability tools, not automatic performance improvements.
    Do not claim a performance benefit without measurement. Watch for eager graph creation,
    expanded singleton lifetimes, retained `Context`/Activity references, duplicate Flow
    collectors, and work that has moved onto the main thread.
12. Add focused unit tests before or with each migration, using constructor-provided fakes/mocks to
    cover success, failure, cancellation, and delegation as applicable. When the Hilt graph or
    Android entry points change, also add or run an integration test that constructs the affected
    path; unit tests that instantiate the class directly do not validate Hilt wiring.
13. Minimum validation for a non-player DI migration is `ktlintCheck`,
    `:app:testGithubDebugUnitTest`, `:app:compileGithubDebugKotlin`, and
    `:app:compileFossDebugKotlin`, followed by the relevant flavor build. Exercise the affected UI
    or background flow on a device/emulator, including configuration change and an error path. A
    player/startup migration additionally requires the player golden paths above and relevant
    startup/playback measurements. Do not describe a migration as safe, risk-free, or behaviorally
    identical based only on compilation or unit tests; state exactly what was verified and what was
    not.

## Strings — no hardcoded strings

All user-facing strings MUST be declared in `app/src/main/res/values/strings.xml` and referenced via `stringResource(R.string.xxx)` (or `context.getString(...)` outside Compose) — never inline string literals in UI code. When adding a string, add it to `strings.xml` first, then reference it. Do not touch other locales' `strings.xml` files — only the default (English) resource file.

## Code structure and modularization — where every file goes

This is the authoritative placement guide. Follow it for every new file and every refactor. It is
not aspirational: `ui/components/shared/`, `ui/components/library/`, `ui/components/music/*` and
`ui/components/layout/topbar/` are already built this way, and new work must match them.

### The package map

| Package | Holds | Visibility of its declarations |
| --- | --- | --- |
| `ui/screens/<feature>/` | The route entry composable, its ViewModel, and pure state/policy helpers for that feature only | `internal` or `private` |
| `ui/components/<feature>/` | Composables owned by one feature but reused across several screens or routes inside it | `internal` |
| `ui/components/shared/` | Cross-feature building blocks with no feature knowledge (music AND video AND library all use them) | `public` |
| `ui/components/layout/` | App chrome: top bars, scaffolds, navigation surfaces | `public` |
| `ui/theme/` | Colour, type, shape and motion tokens. Every literal colour lives here, never at a call site | `public` |
| `ui/utils/` | Compose-only helpers (modifiers, form factor, fading edges) | `public` |
| `utils/` | Non-Compose helpers (formatters, parsers, dispatchers). **Do not add a second utils package** | `public` |

A file in `ui/screens/` may **not** be imported by another feature's screen package. If two
features need it, it moves — that is the whole signal.

### The placement decision tree

Answer these in order for any composable, and stop at the first "yes":

1. **Used only inside this one file, and under ~40 lines?** Keep it `private` in the file.
2. **Used by only one screen, but the screen file is over budget?** Split it into a sibling file in
   the same `ui/screens/<feature>/` package, `internal`.
3. **Used by two or more screens or routes of the same feature?** Move it to
   `ui/components/<feature>/`, `internal`.
4. **Used by two or more different features, or encodes no feature vocabulary at all** (a chip, a
   badge, a row, an empty state, a search field, a thumbnail)? Move it to `ui/components/shared/`,
   `public`, and strip every feature-specific parameter on the way.

**Promote on the second real consumer, never on the first speculative one.** A component with one
call site that "might be reused later" belongs next to its call site. Guessing wrong produces a
shared API shaped for exactly one screen, which is worse than a duplicate.

**Demote too.** If a `shared/` component ends up with a single caller after a refactor, move it back
down. `shared/` is a claim about actual reuse, not a graveyard.

### When something earns its own folder

Create `ui/components/<feature>/` when the feature owns **three or more** component files, or when
one screen's components exceed ~600 lines in total. Below that they stay as sibling files in the
screen package.

Sub-folder a feature package (as `ui/components/music/` already is) once it passes **eight files**.
Split by *role*, not by screen: `card/`, `item/`, `row/`, `section/`, `header/`, `detail/`, `sheet/`,
`common/`. Do **not** create `utils/`, `misc/`, `helpers/` or `other/` — a folder that cannot be
named by role is a folder that should not exist.

### File size budgets

| File kind | Target | Hard ceiling |
| --- | --- | --- |
| Screen entry (`*Screen.kt`) | 250 lines | 400 |
| Component file | 300 lines | 500 |
| ViewModel | 400 lines | 600 |
| Anything else | 400 lines | 600 |

Over the ceiling, splitting is mandatory before the change lands. Under it, do **not** split a file
you are not otherwise working in — this follows the same ratchet philosophy as Spotless: new and
touched code meets the bar, untouched legacy code is left alone until someone has a reason to open
it.

### How to split a file

Split by **responsibility**, never by line count. In order of preference:

1. **Lift the leaves.** Move the leaf composables out first; the screen keeps layout and state.
2. **Separate state from pixels.** Pure functions (filtering, sorting, grouping, formatting) move to
   their own file and become unit-testable — `HomePagination.kt`, `SubscriptionEnrichmentPolicy.kt`
   and `HomeUiStateNormalization.kt` are the pattern to copy.
3. **Group by surface.** A screen with a list, a header and three sheets becomes `FooScreen.kt` +
   `FooHeader.kt` + `FooList.kt` + `FooSheets.kt`.
4. **Never split into `FooScreen2.kt`, `FooScreenPart2.kt` or `FooExtra.kt`.** Every file name must
   describe what is inside it.

### Naming conventions (already in force — match them)

- `shared/` primitives with no domain meaning take the **`Flow`** prefix: `FlowFilterChip`,
  `FlowSearchField`, `FlowEmptyState`, `FlowLoadingIndicator`.
- `shared/` components in the media vocabulary take the **`Media`** prefix: `MediaRow`,
  `MediaThumbnail`, `MediaBadges`, `MediaKindSelector`.
- Feature components take the **feature** prefix: `LibraryShelf`, `MusicTrackItem`,
  `PlaylistDetailComponents`, `HistoryComponents`.
- The file is named after its primary export. One dominant export per file; a file named
  `*Components.kt` or `*Rows.kt` may hold a small family of siblings and nothing else.
- Private `const val` are `SCREAMING_SNAKE_CASE`; private `val` holding a `Dp`/`Color`/spec token
  stay `PascalCase`. ktlint enforces both.

### Sharing without redesigning

When two implementations differ, decide deliberately:

- **Identical output, different call sites** → merge into one component. Delete the loser.
- **Same shape, different values** → merge, and expose the difference as a **parameter whose default
  preserves today's behaviour**. `VideoCardFullWidth(useInternalPadding = true)` and
  `PlaylistCard(useInternalPadding = true)` are the reference: the default keeps every existing call
  site pixel-identical, and the container-padded screens opt out explicitly.
- **Same name, genuinely different purpose** → leave both. Two components serving two screens with
  different jobs are not duplication, and merging them produces a parameter bag nobody can read.
- **Different design** → stop. Consolidation is not a licence to change how anything looks. If the
  only way to share code is to change one surface's appearance, that is a design decision and it
  must be raised, not assumed.

**A refactor that changes pixels is not a refactor.** Visual and behavioural output must be
identical before and after, unless the task explicitly asked for a visual change.

### What a screen file is allowed to contain

The route composable, its state hoisting, its effects and its layout. Not: bespoke cards, bespoke
rows, bespoke empty/error states, bespoke badges, bespoke formatters, or a second copy of a
`shared/` component. Before writing any of those, run `graphify query` and read
`ui/components/shared/` — the app already has `FlowEmptyState`, `FlowErrorState`, `MediaRow`,
`MediaThumbnail`, `MediaBadges`, `FlowSearchField`, `ShimmerLoading` and `FastScrollbar`.

### ViewModels

- One ViewModel per feature, in `ui/screens/<feature>/`, `@HiltViewModel` plus constructor injection.
- A ViewModel shared by several routes is obtained through an explicit activity-scoped accessor
  (`sharedMusicViewModel()`, `sharedMusicPlayerViewModel()`), never a bare `hiltViewModel<T>()` at
  each route. See the performance rules above — this is a measured regression, not a style choice.
- Pure logic (paging, filtering, normalization, policy) comes **out** of the ViewModel into its own
  file so it can be unit-tested without Android.

### Refactor hygiene

1. **No dead code.** Delete, never comment out. A moved component leaves nothing behind.
2. **No new comments.** Comments explain non-obvious WHY only (a workaround, a hidden constraint, a
   subtle invariant) — never restate WHAT the code already says.
3. **Moving a file makes it ratchet-eligible.** Spotless `ratchetFrom` treats a moved or touched file
   as new, so it must now pass ktlint in full, including import order and property naming. Budget for
   that; it is the most common cause of a surprise red build during a move.
4. **Strings move with the component**, and only in `values/strings.xml`. Never touch other locales.
5. **Run `graphify update .`** after each landed refactor step so the next query is accurate.

## Rules for working on the project

1. Always pull the latest changes from `main` before starting work to minimize merge conflicts.
2. Commit messages should be clear and follow the format: `type(scope): short description` (e.g. `feat(player): add gapless playback`). Scope is optional.
3. Follow current Kotlin and Android best practices — when unsure, check official docs rather than guessing.
4. DO NOT edit the app's Room database schema without explicit instruction (schema changes require a version bump and migration, handled deliberately).
5. DO NOT bump the app version in any file — version bumps are done manually by the project owner.
6. Every PR with a change users can notice adds a nightly note: one new file
   `.github/nightly-notes/YYYY-MM-DD-<branch>.md` (the date the PR is opened, the branch name in
   lowercase with `/` replaced by `-`) holding `## New`, `## Improved` or `## Fixed` headings, each
   with one short `- ` bullet per change, written for users, issues as `(#123)`, no em-dashes. One
   file per PR, never edit another PR's file. CI, test and refactor-only PRs add none. CI publishes
   every note as the nightly release body that the in-app update screen shows, and the Checks job
   fails on a malformed note (`python3 .github/scripts/nightly_notes.py --check`). This is separate
   from the release changelog in `app/src/main/assets/changelog/v*.txt`; at a stable release the
   notes are folded into it and into `release-notes.md`, and the note files are deleted (keep the
   folder's `README.md`).

## AI-only guidelines

1. Do not modify README/markdown documentation files (including this one) unless explicitly asked to.
2. Unless explicitly requested and authorized, do not commit, push, or merge changes. Never rewrite git history, force-push, or delete branches without explicit human instruction.
3. Follow the guidelines and instructions given by the project owner over any default assumption.
4. Ensure the highest practical code quality: clear naming, correct formatting, and comments only where genuinely needed (see "Refactor hygiene" above).
5. If a task is ambiguous, ask rather than guessing at requirements or implementation details.
6. Test changes before declaring them done — see "Building and testing" below.

## Kotlin formatting and linting

Spotless enforces ktlint formatting using the rules in `.editorconfig`. It checks Kotlin sources in
`app/src/` and `benchmark/src/`, plus the selected project Gradle Kotlin scripts. Build output,
generated sources, and ignored reference projects are outside the target set.

1. Before committing or pushing Kotlin or Gradle Kotlin script changes, run:

```bash
./gradlew ktlintCheck
```

On Windows PowerShell, use `.\gradlew.bat ktlintCheck`.

2. To automatically format targeted files changed since the lint ratchet revision, run:

```bash
./gradlew ktlintFormat
```

On Windows PowerShell, use `.\gradlew.bat ktlintFormat`. Review the resulting diff before committing.

3. Do not bypass, disable, or weaken the formatter to make a change pass. Fix the reported file or
update `.editorconfig` only when the project convention itself is intentionally changing.
4. The repository adopts formatting incrementally with Spotless `ratchetFrom`, so legacy untouched
files are not reformatted. A newly added file or an existing targeted file changed after the ratchet
revision must pass the configured ktlint rules.
5. GitHub Actions runs `spotlessCheck` before tests and builds. A formatting violation fails the
`Build APK` job. Add any future first-party Kotlin module to the Spotless target list explicitly.

## Building and testing your changes

1. After making changes, build the relevant flavor to check for compilation errors, e.g.:

```bash
./gradlew :app:assembleGithubDebug
```

2. If the build fails, fix the reported errors and rebuild before proceeding.
3. For UI changes, actually run the app (emulator or device) and exercise the golden path plus edge cases — passing a build does not mean the feature works correctly.

## Baseline profile — when to regenerate

The app ships a generated baseline profile at `app/src/main/generated/baselineProfiles/`
(`baseline-prof.txt` drives ART's AOT compilation; `startup-prof.txt` drives dex layout). It is
generated on a real device by the `:benchmark` module, and the generated files **are committed**.
`baselineProfile { mergeIntoMain = true }` makes every shipped variant (github, foss, nightly) read
the same files, so foss and nightly startup match github release.

```bash
./gradlew :app:generateGithubReleaseBaselineProfile
```

**Regenerate when:**

1. Before tagging a release, if the profile has not been regenerated since the last one.
2. After changing the cold-start path — `MainActivity.onCreate`, `FlowApp`, app-level DI graph,
   theme resolution, or player/cache initialization.
3. After changing a journey the generator exercises (app launch, Home feed scroll), or after
   editing `BaselineProfileGenerator` itself.
4. After a Compose, Media3, or AGP/Kotlin upgrade that shifts which framework classes run.

**Do not regenerate** for routine feature or UI work that does not touch the above. It is a ~20 min
run that occupies a physical device, and the resulting diff is thousands of lines of churn.

**Requirements and gotchas:**

- Needs a connected physical device (`useConnectedDevices = true`). On MIUI/HyperOS, Developer
  options must have **both** "USB debugging (Security settings)" (grants `INJECT_EVENTS`) and
  "Install via USB". Pass `-PbaselineProfileEmulator=true` to use the managed Pixel 6 instead.
- Keep the device awake and connected for the whole run; a disconnect fails the task outright.
- `startup()` must **not** settle past first frame. `startActivityAndWait()` already returns
  there, and adding a `waitForIdle` sweeps the feed load into `startup-prof.txt`, which then
  asserts nearly the whole app is startup-critical and leaves R8 unable to fit the set into
  `classes.dex`. Keep `startup-prof.txt` a genuinely small subset of `baseline-prof.txt`.
- Profile size is **not** a measure of startup work: it records everything executed during the
  journey on any thread, so moving work to a background thread keeps it in the profile. Use
  `StartupBenchmark` (`:benchmark:connectedBenchmarkReleaseAndroidTest`) to measure.
