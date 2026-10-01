# Snake Game — Modernization Architecture

## Summary

This document defines the full modernization plan for the Snake game. The project is a small (~340 lines) Java/Swing implementation with no build tool, no tests, no CI, several bugs, and a minimal UI. The goal is a polished, maintainable game that feels intentional in 2026 — not just fixed up, but something a developer would be proud to show.

**Most important first step: GitHub Actions CI.** The Maven build (PR#1) is the prerequisite; CI is the first item that must follow, because every other modernization PR needs an automated pass/fail signal to be safely reviewable by the pipeline.

---

## Current-state assessment

### Architecture

The project has six classes, all in the default package, all in `src/`:

| Class | Role |
|---|---|
| `Main` | Entry point; creates the window |
| `Window` | `JFrame` subclass; owns the grid, starts the game thread |
| `ThreadsController extends Thread` | Game loop, snake movement, collision, food — everything |
| `DataOfSquare` | Holds a `SquarePanel` and maps int color codes to `Color` objects |
| `SquarePanel extends JPanel` | Single colored cell |
| `KeyboardListener extends KeyAdapter` | Reads arrow keys, writes to a static field |
| `Tuple` | x/y pair with two unused fields (`xf`, `yf`) |

All game state, rendering, and the game loop are collapsed into `ThreadsController`. There is no separation between logic and presentation.

### Code quality

- **`ThreadsController.directionSnake` is `static`** (`ThreadsController.java:10`). Any class can write to it; it makes unit testing impossible and concurrent instances share state.
- **Magic numbers everywhere.** Direction codes (0–4), color codes (0–2), and grid size (20) appear as bare integers throughout. No constants, no enums.
- **Unused dead code.** `Tuple.xf` and `Tuple.yf` (`Tuple.java:4–5`) are declared and never read.
- **Non-Java naming.** Fields `C`, `Squares`; methods `ChangeData`, `ChangeColor`, `lightMeUp` — mix of Pascal case and arbitrary casing violates Java conventions throughout.
- **No package structure.** All classes are in the default package; IDEs and tools handle this poorly.

### Bugs

| Bug | Location | Impact |
|---|---|---|
| `stopTheGame()` loops forever | `ThreadsController.java:73–77` | Game over hangs the game thread; the only recovery is to kill and reopen the process |
| Food spawn hardcodes 19 instead of grid constant | `ThreadsController.java:88–89` | If grid size ever changes, food can spawn off-grid |
| `getValAleaNotInSnake()` resets loop index to 0 on conflict | `ThreadsController.java:95` | Retry loop is O(n²) and biased; can theoretically loop forever on a near-full board |
| `repaint()` called from game thread | `DataOfSquare.java:21` → `SquarePanel.java:14` | Swing UI must be updated on the Event Dispatch Thread; this is a threading violation that causes intermittent rendering glitches |
| Docker compose references wrong jar name | `docker-compose.yml:7` | `Snakegame.jar` (lowercase g) vs actual `SnakeGame.jar`; Docker setup is broken |

### Build and tooling

- No build tool. Compilation requires manual `javac`. No repeatable build.
- Prebuilt `SnakeGame.jar` committed to the repo. The committed artifact can drift from the source.
- `.gitignore` is a generic Visual Studio / Eclipse template with no Java/Maven entries.
- Dockerfile uses the committed jar directly rather than building from source.
- No tests. No CI. No linting. No formatting enforcement.

### UI/UX

- 20×20 grid of plain `JPanel` cells with `Color.white` (snake), `Color.BLUE` (food), `Color.darkGray` (empty). No styling.
- No menu screen, no game over screen, no score display, no pause, no restart. The README says: "If you lose, just close it and re-open it."
- Window is 300×300 pixels with no minimum size; cells are tiny.
- No sound.

---

## Decision records

### ADR-1: Platform — stay on Java/Swing with FlatLaf

**Context.** Three realistic options:

| Option | Modern look | Agent buildability / UI tests | Distribution | Maintainability |
|---|---|---|---|---|
| Keep Java, modernise Swing (FlatLaf) | Good with FlatLaf | `mvn verify`; Swing UI test with AssertJ-Swing or FEST | Single jar | Good |
| Rewrite in JavaFX | Very good | Maven; TestFX for UI tests | Requires JavaFX runtime or jlink | Good |
| Rewrite as web app (JS/Canvas) | Excellent | npm; Playwright; trivial CI | Browser | Depends on framework |

**Decision:** Stay on Java, add FlatLaf for the UI theme.

**Reasoning:**
- Incremental migration is required (no big-bang rewrites; a playable version must exist at every phase boundary).
- FlatLaf drops in as a single Maven dependency and immediately gives a modern, flat look without touching any rendering code.
- JavaFX would require moving all rendering code and the full build setup; risk is higher for no material gain at this scale.
- A web rewrite is a complete rewrite with zero code reuse; the project is simple enough that the Java version can be made fully polished.

**Consequences.** The `javax.swing.Timer` replaces `Thread.sleep` as the game loop mechanism, eliminating the threading violations. FlatLaf is the single external runtime dependency.

### ADR-2: Game loop — replace `Thread.sleep` with `javax.swing.Timer`

**Context.** `ThreadsController extends Thread` drives the game loop with `Thread.sleep(speed)`. All rendering calls happen on that thread, not the Event Dispatch Thread (EDT), violating Swing's threading model.

**Decision:** Replace the game thread with `javax.swing.Timer` firing on the EDT. Game state updates happen in the timer callback. A single `panel.repaint()` call at the end of each tick triggers rendering.

**Consequences.** Thread-safety issues disappear. The `ThreadsController` class is deleted. Game logic moves to a pure `GameEngine` class with no threading concerns.

### ADR-3: Package structure — `com.snake.*`

**Context.** All classes are in the default package. This is incompatible with proper Java tooling, prevents package-private visibility scoping, and blocks future module support.

**Decision:** Move all classes to `com.snake.*` with sub-packages `core`, `ui`, `input`.

**Consequences.** All import statements must be updated. Maven source layout changes to `src/main/java`. This is a one-PR mechanical rename.

---

## Target architecture

### Module breakdown

```
com.snake
├── Main                        Entry point: builds window, starts game
├── core
│   ├── Direction               Enum: UP, DOWN, LEFT, RIGHT (replaces int codes)
│   ├── Cell                    Enum: EMPTY, SNAKE_HEAD, SNAKE_BODY, FOOD
│   ├── Position                Immutable (row, col) value type (replaces Tuple)
│   ├── GameState               Pure value: grid, snake deque, food pos, score, status
│   ├── GameEngine              Tick logic: move, collision, eat, grow (no threading)
│   └── GameStatus              Enum: RUNNING, PAUSED, GAME_OVER
├── ui
│   ├── GameWindow              JFrame: owns card layout, switches between panels
│   ├── MenuPanel               JPanel: title, Play, High Score, Settings
│   ├── GamePanel               JPanel: renders GameState; owns javax.swing.Timer
│   ├── PauseOverlay            JPanel: semi-transparent overlay over GamePanel
│   ├── GameOverPanel           JPanel: score, high score, Play Again, Menu
│   └── SettingsPanel           JPanel: speed slider, key binding display
└── input
    └── InputHandler            KeyAdapter: maps keys to Direction; decoupled from game
```

### Data flow

```
KeyEvent → InputHandler → GamePanel (queues next direction)
                                ↓
javax.swing.Timer tick → GameEngine.tick(state, direction) → new GameState
                                ↓
                         GamePanel.repaint() → renders GameState
```

`GameState` is immutable. `GameEngine.tick()` returns a new `GameState`. No shared mutable fields.

### Folder structure

```
Snake/
├── pom.xml
├── src/
│   ├── main/java/com/snake/   (all production code)
│   └── test/java/com/snake/   (all tests)
├── docs/
│   └── ARCHITECTURE.md
└── .github/
    └── workflows/
        ├── ci.yml             (build + test on every PR)
        └── release.yml        (publish GitHub Release on tag)
```

---

## Modernization areas

### 1. Project foundation and build — MUST

**Why:** No build tool means no repeatable build, no dependency management, no CI gate.

**What:** Maven `pom.xml` (PR#1 done) + GitHub Actions `ci.yml` that runs `mvn verify` on every PR and push to `master`. CI status check must be green before any PR can be merged.

**Scope:** `pom.xml`, `.github/workflows/ci.yml`. No source code changes.

**Note:** PR#1 adds Maven. The CI workflow is the immediate follow-on and is the most important single item in the roadmap.

### 2. Remove committed binary and fix Docker — MUST

**Why:** `SnakeGame.jar` committed to the repo can silently diverge from source. The Docker setup is broken (wrong jar filename, copies prebuilt artifact instead of building).

**What:** Remove `SnakeGame.jar` from git tracking. Update `Dockerfile` to run `mvn package` and copy from `target/`. Fix the filename typo in `docker-compose.yml`.

**Scope:** `.gitignore`, `Dockerfile`, `docker-compose.yml`. No source changes.

### 3. Architecture refactor — MUST

**Why:** Game logic, threading, and rendering are fused into `ThreadsController`. There is no testable unit and no seam to add features safely.

**What:** Introduce `GameState`, `GameEngine`, `Direction`, `Cell`, `Position` in `com.snake.core`. Delete `ThreadsController`. Replace `DataOfSquare`/`SquarePanel`/`Tuple` with the new types. Move to `com.snake.*` package structure.

**Scope:** All source files. This is the largest change; it is phased across multiple PRs (see Roadmap).

**Out of scope:** No new gameplay features during this refactor.

### 4. Bug fixes — MUST

**Why:** `stopTheGame()` makes the game unplayable after one collision. The food-spawn hardcoding is a latent defect.

**What:**
- Replace `stopTheGame()`'s infinite loop with a state transition to `GameStatus.GAME_OVER` and display the game over screen.
- Replace hardcoded `19` in food spawn with `GameState.GRID_SIZE - 1`.
- Move all `repaint()` calls to the EDT via `SwingUtilities.invokeLater` until the Timer refactor lands.

**Scope:** `ThreadsController`, later `GameEngine`.

### 5. Game loop and rendering — MUST

**Why:** `Thread.sleep` in the game thread is a Swing threading violation. Rendering is row-by-row cell updates rather than a single clean paint.

**What:** Replace `ThreadsController extends Thread` with `javax.swing.Timer` in `GamePanel`. One `repaint()` per tick. `GamePanel.paintComponent()` iterates `GameState.grid` and draws the full board in one pass.

**Scope:** New `GamePanel`, deleted `ThreadsController`, deleted `DataOfSquare`/`SquarePanel`.

### 6. Testing and CI — MUST

**Why:** Zero tests means every refactor is blind. The Code Reviewer needs an automated signal.

**What:** Add JUnit 5 + AssertJ to `pom.xml`. Write unit tests for `GameEngine`: movement in all four directions, wall wrapping, self-collision, food consumption, score increment, grow logic. Minimum 80% line coverage on `com.snake.core` enforced by JaCoCo in CI.

**Scope:** `src/test/java/com/snake/core/`. No UI tests in this phase.

### 7. UI/UX and visual design — MUST

**Why:** The current UI is four colors on a blank JFrame. There is no menu, no score, no restart, no pause — it is not a game someone would choose to play.

**What:** See UI/UX direction section. FlatLaf theme, menu screen, game over screen, pause overlay, score HUD. All screens keyboard-navigable.

**Scope:** `com.snake.ui.*`. Game logic unchanged.

### 8. Gameplay features — SHOULD

**Why:** The game is functional but has no progression or quality-of-life features.

**What:**
- Speed increase as the snake grows (every 5 segments, decrease timer interval by 5ms, floor at 100ms).
- Persistent high score stored in `~/.snake/highscore.properties`.
- Pause toggle (Escape key).
- Current score displayed during gameplay.

**Scope:** `GameEngine`, `GamePanel` HUD, `SettingsPanel`.

### 9. Controls and accessibility — SHOULD

**Why:** Arrow-key-only controls exclude WASD users. No accessibility considerations exist.

**What:**
- WASD support alongside arrow keys.
- All interactive UI elements (buttons, settings) must be keyboard-navigable (Tab + Enter).
- Minimum 14pt font for all text.
- Food cell rendered with a distinct shape (circle vs the square snake body) so color is not the only differentiator.

**Scope:** `InputHandler`, `GamePanel`, all UI panels.

### 10. Audio — COULD

**Why:** Sound effects add polish but are not essential for the game to feel modern.

**What:** Short sound effects for food eaten and game over via `javax.sound.sampled`. Mute toggle in settings. Bundled as resources in the jar.

**Scope:** New `com.snake.audio.AudioManager`. Bundled `.wav` files in `src/main/resources/`.

**Drop if:** Sound effects are hard to source with an appropriate license. Silence is better than low-quality audio.

### 11. Release and docs — SHOULD

**Why:** Currently, distribution requires cloning the repo and running the jar manually. There is no version.

**What:** GitHub Actions `release.yml` triggered on version tags; produces a GitHub Release with the runnable `SnakeGame.jar` attached. Update README to reflect the Maven build and new screenshot.

**Scope:** `.github/workflows/release.yml`, `README.md`.

---

## Roadmap

Each phase ends with a buildable, playable game. Each PR within a phase is independently reviewable.

### Phase 1 — Foundation (prerequisite: PR#1 merged)

**Goal:** Every subsequent PR has an automated build and test gate.

| PR | Change |
|---|---|
| CI workflow | Add `.github/workflows/ci.yml`; runs `mvn verify` on PR and push to master |
| Remove committed jar | Remove `SnakeGame.jar` from git; add Maven and `target/` to `.gitignore` |
| Fix Docker | Build from source in `Dockerfile`; fix jar name typo in `docker-compose.yml` |

**Gate:** `mvn verify` passes in CI. Game still launches via `java -jar target/SnakeGame.jar`.

### Phase 2 — Critical bug fixes

**Goal:** Game over no longer freezes the process.

| PR | Change |
|---|---|
| Direction enum | Add `Direction` enum; replace int constants in `ThreadsController` and `KeyboardListener` |
| Fix stopTheGame | Replace infinite loop with a flag; show "GAME OVER — close to restart" text on the frame title as a stub |
| Fix food spawn | Replace hardcoded `19` with `Window.width - 1` / `Window.height - 1` |
| EDT fix | Wrap `lightMeUp` calls in `SwingUtilities.invokeLater` |

**Gate:** Game over is recoverable (close and reopen without task-manager). No compiler warnings.

### Phase 3 — Architecture refactor

**Goal:** Game logic is testable; all classes in `com.snake.*`.

| PR | Change |
|---|---|
| `Position` + `Cell` + `Direction` | Immutable value types in `com.snake.core` |
| `GameState` | Pure state record; no Swing dependencies |
| `GameEngine` | Tick logic extracted from `ThreadsController`; `GameEngine.tick()` returns new `GameState` |
| Package rename | Move all classes to `com.snake.*`; update `pom.xml` source layout to `src/main/java` |
| Delete legacy classes | Remove `ThreadsController`, `DataOfSquare`, `SquarePanel`, `Tuple`, `KeyboardListener` |
| `GamePanel` with Timer | New `GamePanel` owns `javax.swing.Timer`; renders from `GameState` |

**Gate:** `mvn verify` passes. Game plays identically to before. No Swing threading violations.

### Phase 4 — Testing

**Goal:** Core logic has automated test coverage enforced by CI.

| PR | Change |
|---|---|
| Test framework | Add JUnit 5 + AssertJ + JaCoCo to `pom.xml` |
| `GameEngineTest` | Movement, wall wrap, self-collision, food eaten, score, grow logic |
| CI coverage gate | JaCoCo minimum 80% line coverage on `com.snake.core` fails the build if missed |

**Gate:** `mvn verify` runs tests; coverage gate enforced. No regressions.

### Phase 5 — UI/UX

**Goal:** The game feels modern and complete.

| PR | Change |
|---|---|
| FlatLaf theme | Add FlatLaf dependency; apply `FlatDarkLaf.setup()` in `Main` |
| `MenuPanel` | Title, Play button, high score display |
| `GameWindow` card layout | `CardLayout` switching between Menu, Game, GameOver, Settings |
| Game panel redesign | Dark background, grid lines, styled snake and food cells |
| `GameOverPanel` | Score, high score, Play Again button, Menu button |
| Pause overlay | Escape toggles pause; semi-transparent overlay |
| Score HUD | Current score and high score shown during play |
| `SettingsPanel` | Speed slider |

**Gate:** All screens reachable by keyboard. WCAG AA color contrast on text. Minimum 14pt fonts.

### Phase 6 — Gameplay and accessibility

**Goal:** Controls and progression complete the player experience.

| PR | Change |
|---|---|
| WASD controls | Add W/A/S/D to `InputHandler` |
| Speed progression | Timer interval decreases every 5 segments (floor 100ms) |
| High score persistence | `~/.snake/highscore.properties` read on start, written on game over |
| Food shape | Draw food as a filled circle; snake body as rounded rect |
| Accessibility | Tab order on all panels; food shape cue for color blindness |

**Gate:** Playable with WASD. High score survives restart.

### Phase 7 — Audio and release (optional)

| PR | Change |
|---|---|
| Audio manager | `javax.sound.sampled` for eat/game-over sounds; mute toggle |
| Release workflow | GitHub Actions on tag push; attaches `SnakeGame.jar` to a GitHub Release |
| README update | New screenshot, Maven build instructions, gameplay controls |

---

## Quality gates and conventions

### Every PR must pass

1. `mvn verify` exits 0 (compile + tests + JaCoCo coverage gate).
2. Zero compiler warnings (`-Xlint:all` in `pom.xml` from Phase 3 onward).
3. Checkstyle passes with Google Java Style configuration.
4. No Swing calls outside the EDT (enforced by inspection; flagged in code review).
5. The game launches (`java -jar target/SnakeGame.jar`) without error output on stdout/stderr.

### Test coverage requirement

- Minimum **80% line coverage** on `com.snake.core` measured by JaCoCo.
- `com.snake.ui` and `com.snake.input` are excluded from the coverage gate (UI tests are deferred).

### Coding conventions

- Java 11, Google Java Style (2-space indent, 100-char line limit).
- `final` on all fields that are not reassigned.
- No public mutable static fields.
- Enums for all fixed sets of values (direction codes, cell types, game status).
- No raw `Thread`; use `javax.swing.Timer` for timed UI work.
- One top-level class per file; package-private visibility by default.

### Branch and commit conventions

- Branch names: `feat/<short-description>`, `fix/<short-description>`, `docs/<short-description>`.
- Commit subject: imperative, ≤72 chars (e.g. `fix: replace stopTheGame infinite loop with GAME_OVER state`).
- One logical change per PR. No "misc cleanup" PRs.
- PRs target `master`. Squash merge.

---

## UI/UX direction

### Color palette

#### Dark theme (default)

| Element | Color | Hex |
|---|---|---|
| Background | Deep navy | `#0F0F23` |
| Grid lines | Subtle dark | `#1A1A3A` |
| Snake head | Bright mint | `#00FF87` |
| Snake body | Teal | `#00C9A7` |
| Food | Coral | `#FF6B6B` |
| Text primary | Light gray | `#E0E0E0` |
| Text secondary | Muted | `#8888AA` |
| Button background | Dark card | `#1E1E3A` |
| Button hover | Slightly lighter | `#2A2A50` |
| Accent | Mint | `#4ECCA3` |

#### Light theme (toggled in Settings)

| Element | Color | Hex |
|---|---|---|
| Background | Off-white | `#F5F5F5` |
| Grid lines | Light gray | `#E0E0E0` |
| Snake head | Forest green | `#2D6A4F` |
| Snake body | Medium green | `#40916C` |
| Food | Red | `#D62828` |
| Text primary | Near black | `#1A1A1A` |
| Button background | White | `#FFFFFF` |

### Typography

- **FlatLaf system font** (Inter on Windows, SF Pro on Mac, Noto Sans on Linux).
- Game title (menu): 48pt bold.
- Score label: 18pt bold, monospace (score digits don't shift layout as they grow).
- Button label: 16pt medium.
- Body / instructions: 14pt regular. Never smaller than 14pt.

### Screens

**Menu**
- Centered vertically and horizontally.
- Title "SNAKE" at top in accent color.
- High score below title in secondary text color.
- Single "PLAY" button (primary action, large, accent background).
- "SETTINGS" text button below.

**Game**
- Full window is the game grid.
- Score HUD in top bar: "SCORE 000" left, "BEST 000" right.
- Grid lines drawn but subtle (low-contrast, 1px).
- Snake head: slightly larger / brighter than body segments.
- Food: circle (not square) to distinguish it from the grid by shape alone.
- Pause hint: small "ESC to pause" text at the bottom, fades after 3 seconds.

**Pause overlay**
- Semi-transparent dark overlay (60% opacity) over the frozen game.
- "PAUSED" in large text, centered.
- "RESUME" and "QUIT TO MENU" buttons below.

**Game over**
- Slide-in panel over the game (not a new screen; keeps the final board visible beneath).
- "GAME OVER" in large coral text.
- "SCORE: 42" and "BEST: 99" below.
- "PLAY AGAIN" (primary) and "MENU" (secondary) buttons.

**Settings**
- Speed: slider from "Slow" to "Fast" (maps to timer interval 250ms → 100ms).
- Theme toggle: Dark / Light.
- "BACK" button returns to menu.

### Animation principles

- Game ticks are discrete (grid-based movement only; no sub-pixel interpolation in scope).
- Game over panel slides in from bottom over 200ms (ease-out).
- Button hover: background lightens over 100ms.
- No animations that can interfere with game input timing.

### Accessibility requirements

- All buttons reachable by Tab key; activated by Enter/Space.
- Focus indicator: 2px accent-color outline on focused element.
- Food cell: circle shape (not just color difference from snake body).
- Minimum contrast ratio 4.5:1 for all text (WCAG AA).
- Window resizable; minimum size 420×480. Grid scales proportionally.

---

## Risks and assumptions

| # | Risk / Assumption | Mitigation |
|---|---|---|
| 1 | **Assumed:** FlatLaf license (Apache 2.0) is compatible with distribution. | Verify before adding the dependency. |
| 2 | **Assumed:** Java 11 is the target runtime. The Dockerfile uses openjdk:17. | Pin to Java 17 in `pom.xml` to match Docker; or explicitly standardise on 11. Needs a human decision. |
| 3 | **Risk:** The x/y coordinate conventions in `ThreadsController` are internally inconsistent (row vs column confusion). A mechanical rename could introduce subtle movement bugs. | Cover all movement directions with unit tests in Phase 4 before any coordinate cleanup. Test output against known game states. |
| 4 | **Assumed:** Single-player only. The commented-out second-snake code in `Window.java` is dropped. | Confirmed by project goal ("polished single-player beats a long feature list"). |
| 5 | **Risk:** High score stored in `~/.snake/highscore.properties` may not be writable in sandboxed or read-only environments (CI, Docker). | Silently skip persist on write failure; high score degrades gracefully to session-only. |
| 6 | **Assumed:** No sound assets are available under a free license yet. | Audio (Phase 7) is COULD priority and can be dropped without affecting any other phase. |
| 7 | **Risk:** Moving from default package to `com.snake.*` touches every file in one PR. | Do the rename in a single mechanical PR with no logic changes so diff review is straightforward. CI catches any missed references. |
