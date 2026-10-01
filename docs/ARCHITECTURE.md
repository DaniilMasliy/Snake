# Snake Game — Modernization Architecture (v2)

## Summary

The project is a small Java/Swing Snake game (~340 lines, 7 classes, default package). **The goal is to make it look and feel like a real 2026 game.** Visual enhancement is the primary objective; backend improvements are targeted at the bugs that actively break the game and the minimal structure needed to support good rendering.

**Current progress:**
- Maven build is in place (`pom.xml`, Java 11, `src/` source root). `mvn package` produces `target/SnakeGame.jar`.
- Docker fix PR is open (not yet merged).
- Source code is entirely unchanged from the original. All original bugs are present.
- No CI, no tests, no FlatLaf, no game-over screen, no menu, no score display.

**What still needs to happen — in priority order:**
1. CI gate (required before anything else merges)
2. Visual: theme, custom rendering, styled cells, menu, HUD, game-over, pause
3. Backend: replace `Thread.sleep` game loop, fix the game-over hang, add tests

---

## Current-state assessment

### Code structure

| Class | Role | Problem |
|---|---|---|
| `Main` | Entry point, creates `Window` | None |
| `Window extends JFrame` | Builds 20×20 `JPanel` grid, starts game thread | Global static `Grid` shared mutable state |
| `ThreadsController extends Thread` | Game loop, movement, collision, food | Does everything; thread-safety violations; hangs on game over |
| `DataOfSquare` | Maps int color codes to cell panel | Rendering tightly coupled to game state |
| `SquarePanel extends JPanel` | One colored cell | 400 separate panels where one canvas would do |
| `KeyboardListener extends KeyAdapter` | Key to static int | Writes to a `static` field |
| `Tuple` | (x, y) pair | Has two unused fields `xf`, `yf`; no Java naming conventions |

### Bugs

| ID | Description | Location | Impact |
|---|---|---|---|
| BUG-1 | `stopTheGame()` loops forever on collision | `ThreadsController.java:73–77` | Game hangs; only recovery is killing the process |
| BUG-2 | `repaint()` called from game thread, not EDT | `DataOfSquare.java:21` | Swing threading violation; intermittent rendering glitches |
| BUG-3 | Food spawn hardcodes `19` instead of grid constant | `ThreadsController.java:88–89` | Off-by-one if grid size ever changes |
| BUG-4 | Food collision check swaps x and y axes | `ThreadsController.java:62` | Works by coincidence on a 20×20 symmetric grid; fragile |
| BUG-5 | Docker compose references `Snakegame.jar` (lowercase g) | `docker-compose.yml:7` | Docker launch fails on Linux |
| BUG-6 | `SnakeGame.jar` committed to repo | repo root | Binary can drift silently from source |

### What is missing

- No CI — code reviewer has no automated pass/fail signal
- No visual theme — plain white/blue/gray cells on a bare JFrame
- No custom rendering — 400 individual JPanels instead of one `paintComponent`
- No menu screen — game starts immediately with no title, no controls shown
- No score display — player cannot see their score during play
- No game-over screen — game hangs; there is no restart
- No pause — Escape does nothing
- No high score — session-only, not persisted
- No tests — game logic cannot be safely changed
- No speed progression — constant speed regardless of snake length

---

## Decisions

### ADR-1: Platform — Java + Swing + FlatLaf

Stay on Java/Swing. Add FlatLaf (Apache 2.0, `com.formdev:flatlaf:3.4`) for theming. One Maven dependency and one `FlatDarkLaf.setup()` call — zero risk, maximum visual lift with no rewrite.

Alternatives rejected: JavaFX (significant migration for same result at this scale); web rewrite (complete rewrite, no code reuse, violates "always playable" constraint).

### ADR-2: Rendering — replace JPanel grid with single custom canvas

Replace the 400-cell `JPanel` grid with a single `GamePanel extends JPanel` that overrides `paintComponent(Graphics2D)`. This is the enabling change for all visual improvements: rounded corners, distinct food shape, anti-aliasing, smooth grid lines, distinct snake head. Impossible to achieve with 400 individual `JPanel` cells.

Migration path: `GamePanel` reads a shared `int[][]` grid state. `ThreadsController` writes state and calls `SwingUtilities.invokeLater(gamePanel::repaint)`. Minimal structural change — no full GameEngine extraction required.

### ADR-3: Game loop — replace `Thread.sleep` with `javax.swing.Timer`

`ThreadsController extends Thread` + `Thread.sleep` drives the game off-EDT. Replacing with `javax.swing.Timer` fires ticks on the EDT, eliminates threading violations (BUG-2), and makes restart/pause trivial: `timer.stop()` / `timer.start()`.

### ADR-4: Java version — standardise on Java 17

`pom.xml` targets Java 11; `Dockerfile` uses `openjdk:17`. Standardise on Java 17 in `pom.xml` to match Docker. Both are LTS; only two properties in `pom.xml` change.

---

## Target structure

Minimal changes to file layout. No package rename — keeping all classes in the default package to minimise diff size and risk.

```
Snake/
├── pom.xml                       Updated: Java 17, FlatLaf dep, JUnit 5 dep
├── src/
│   ├── Main.java                 Updated: FlatDarkLaf.setup(), show MenuPanel first
│   ├── GamePanel.java            NEW: single canvas, paintComponent, owns Timer
│   ├── MenuPanel.java            NEW: title screen
│   ├── GameOverPanel.java        NEW: game-over overlay with score and restart
│   ├── ScoreHud.java             NEW: score and high score bar above the grid
│   ├── ThreadsController.java    Updated: drive via Timer tick, fix bugs
│   ├── Window.java               Updated: CardLayout between Menu and Game
│   ├── KeyboardListener.java     Updated: add WASD, pause key
│   ├── Direction.java            NEW: enum replacing magic int directions
│   ├── Tuple.java                Kept: remove unused xf/yf fields
│   ├── DataOfSquare.java         Deleted: replaced by GamePanel state array
│   └── SquarePanel.java          Deleted: replaced by GamePanel
├── docs/
│   └── ARCHITECTURE.md
└── .github/
    └── workflows/
        └── ci.yml                NEW: mvn verify on every PR and push to master
```

### Data flow (after ARCH-06)

```
KeyEvent -> KeyboardListener -> stores Direction in volatile field
                                          |
javax.swing.Timer tick (EDT) -> ThreadsController.tick(direction)
                                   /           \
                           update int[][]    update score
                           grid state
                                |
                      GamePanel.repaint() -> paintComponent draws full board
```

---

## Work items

### ARCH-01 — GitHub Actions CI

**Why:** No PR can be safely merged without an automated build check. The code reviewer has no signal. This is the prerequisite for all other work.

**What:** Add `.github/workflows/ci.yml` triggering on `pull_request` and `push` to `master`. Sets up Java 17, runs `mvn verify`.

**In scope:** The YAML file only. No source changes.

**Out of scope:** Coverage gate, test runs (no tests yet).

**Done when:**
1. `.github/workflows/ci.yml` exists on master.
2. A PR with a compile error fails the CI check.
3. Current code (compile-only) shows a green check.
4. Branch protection requires green status before merge.

**Depends on:** none (Maven already merged).

---

### ARCH-02 — FlatLaf theme + window sizing

**Why:** The game runs in a bare 300×300 JFrame with no styling. FlatLaf turns it into a modern dark application with one Maven dependency and one method call.

**What:**
- Add FlatLaf to `pom.xml` (`com.formdev:flatlaf:3.4`). Update Java to 17.
- Call `FlatDarkLaf.setup()` in `Main.main()` before anything else.
- Set window minimum size 440×500, center on screen at startup, title "Snake".

**In scope:** `pom.xml`, `Main.java`. No game logic changes.

**Done when:**
1. `mvn verify` exits 0.
2. Window opens with modern dark FlatLaf frame, title "Snake", centered, minimum 440×500.

**Depends on:** ARCH-01.

---

### ARCH-03 — Custom game panel rendering

**Why:** 400 individual `JPanel` cells cannot support rounded corners, distinct shapes, or anti-aliasing. A single `GamePanel` with `paintComponent(Graphics2D)` enables all visual improvements.

**What:**
- Add `GamePanel extends JPanel` with `int[][] grid` (0=empty, 1=body, 2=head, 3=food).
- `paintComponent(Graphics2D g2)`:
  - Enable anti-aliasing.
  - Fill background `#0F0F23`.
  - Draw grid lines `#1A1A3A` (1px).
  - Snake body cells: rounded rect (4px inset, 8px corner radius), fill `#00C9A7`.
  - Snake head cell: same shape, fill `#00FF87`, 1px border `#80FFB8`.
  - Food cell: filled oval (4px inset), fill `#FF6B6B`.
- `ThreadsController` writes to `GamePanel.grid[][]`; calls `SwingUtilities.invokeLater(gamePanel::repaint)`.
- Remove `DataOfSquare.java` and `SquarePanel.java`.
- `Window` adds `GamePanel` in place of the old `GridLayout`.

**Done when:**
1. `mvn verify` exits 0.
2. Dark background, grid lines, rounded snake body, bright distinct head, circular food.
3. No `DataOfSquare` or `SquarePanel` references remain.
4. `repaint()` only called from the EDT.

**Depends on:** ARCH-01, ARCH-02.

---

### ARCH-04 — Score HUD

**Why:** There is no score display at all.

**What:**
- Track `int score` in `ThreadsController`; increment by 10 per food eaten.
- Add `ScoreHud extends JPanel` (height 40px, background `#0A0A1A`):
  - Left: "SCORE  0042" in monospace 16pt bold, `#E0E0E0`.
  - Right: "BEST  0099" in same style.
- `Window` uses `BorderLayout`: `ScoreHud` at `NORTH`, `GamePanel` at `CENTER`.
- High score is session-only here (persistence in ARCH-11).

**Done when:**
1. Score visible during play; increments by 10 per food.
2. Session high score tracked and displayed.
3. Monospace font renders on dark `#0A0A1A` bar.

**Depends on:** ARCH-03.

---

### ARCH-05 — Menu screen

**Why:** Game starts immediately with no title, instructions, or context.

**What:**
- Add `MenuPanel extends JPanel` (background `#0F0F23`):
  - Centred title "SNAKE" in 64pt bold, `#4ECCA3`.
  - Subtitle "Use arrow keys or WASD" in 14pt `#8888AA`.
  - "PLAY" button: 180×50px, background `#4ECCA3`, text `#0F0F23`, 8px radius.
  - "BEST: 0" in 16pt `#8888AA`.
- `Window` uses `CardLayout`: cards `"menu"` and `"game"`.
- PLAY button (or Space/Enter) transitions to game card and starts `ThreadsController`.

**Done when:**
1. Game opens on menu screen.
2. Title, subtitle, Play button render correctly.
3. Play button (and Space/Enter) starts the game.
4. All interactive elements reachable by keyboard.

**Depends on:** ARCH-03.

---

### ARCH-06 — Fix game loop: replace Thread.sleep with javax.swing.Timer

**Why:** `Thread.sleep` off-EDT causes rendering glitches (BUG-2). `stopTheGame()` loops forever on collision (BUG-1). This fix enables clean pause and restart.

**What:**
- Remove `ThreadsController extends Thread`. Make it a plain class with a `tick()` method.
- `GamePanel` owns a `javax.swing.Timer(speed, e -> controller.tick())`.
- On collision: set `gameOver = true`, call `timer.stop()` — no infinite loop.
- Fix BUG-3: replace `Math.random()*19` with `Math.random()*Window.width`.
- All state updates inside `tick()` (EDT) — `repaint()` at end of tick.

**Done when:**
1. Game plays identically — movement, food, growth, collision all work.
2. On collision, game stops cleanly with no infinite loop.
3. No `Thread.sleep` in production code.
4. `mvn verify` exits 0.

**Depends on:** ARCH-03.

---

### ARCH-07 — Game-over screen with restart

**Why:** On collision the game currently hangs. There is no restart. This is the most critical UX bug.

**What:**
- Add `GameOverPanel extends JPanel` overlay (`rgba(15,15,35,0.85)` background):
  - "GAME OVER" in 48pt bold, `#FF6B6B`.
  - "SCORE  0042" in 22pt `#E0E0E0`.
  - "BEST   0099" in 22pt `#4ECCA3` (shows "NEW BEST!" if record broken).
  - "PLAY AGAIN" and "MENU" buttons.
- "PLAY AGAIN" resets `ThreadsController` state and restarts timer.
- "MENU" returns to menu card.
- `R` key = PLAY AGAIN; `M` / `Escape` = MENU.

**Done when:**
1. Overlay appears on collision over frozen grid.
2. Score and session best displayed correctly.
3. PLAY AGAIN resets and restarts from initial state.
4. MENU returns to menu screen.
5. R and M keyboard shortcuts work.
6. No process kill required to play again.

**Depends on:** ARCH-05, ARCH-06.

---

### ARCH-08 — Pause screen

**Why:** No way to pause. Standard game feature.

**What:**
- `Escape` toggles pause: `timer.stop()` / `timer.start()`.
- Pause overlay on `GamePanel`:
  - "PAUSED" in 48pt bold, `#E0E0E0`.
  - "Press ESC to resume" in 14pt `#8888AA`.
- Input during pause ignored except `Escape` (resume) and `M` (menu).

**Done when:**
1. `Escape` during play shows overlay and freezes snake.
2. `Escape` again resumes exactly.
3. Arrow keys / WASD ignored while paused.
4. `M` during pause returns to menu.

**Depends on:** ARCH-06, ARCH-07.

---

### ARCH-09 — Direction enum + WASD controls

**Why:** Direction is `static int` with magic values 0–4 scattered across two classes. WASD is a standard control scheme.

**What:**
- Add `enum Direction { UP, DOWN, LEFT, RIGHT }`.
- Replace `static int directionSnake` with `volatile Direction direction` in `ThreadsController`.
- Update `moveInterne` switch to use enum.
- `KeyboardListener` maps both arrow keys and WASD to `Direction`.

**Done when:**
1. Game playable with WASD and arrow keys.
2. No integer direction constants remain in production code.
3. `mvn verify` exits 0.

**Depends on:** ARCH-06.

---

### ARCH-10 — JUnit 5 unit tests for game logic

**Why:** Zero tests means every change is blind. CI currently enforces compile-only.

**What:**
- Add JUnit 5 (`junit-jupiter:5.10.2`) and AssertJ (`assertj-core:3.25.3`) to `pom.xml` (test scope).
- Add `maven-surefire-plugin:3.2.5`.
- Write `ThreadsControllerTest.java` covering:
  - Movement in all four directions.
  - Wall wrapping (right from col 19 wraps to col 0, etc.).
  - Self-collision detection.
  - Food consumed: size increments, score increments by 10.
  - Food not spawning on an occupied cell.

**Done when:**
1. `mvn test` exits 0 with all tests passing.
2. At least 8 passing tests covering the listed scenarios.
3. CI runs tests on every PR.

**Depends on:** ARCH-09.

---

### ARCH-11 — Speed progression + persistent high score

**Why:** Constant speed has no difficulty curve. Score not persisting across sessions is discouraging.

**What:**
- Speed: decrease timer interval by 3ms per food eaten; floor at 80ms. Initial: 200ms.
- Persist high score to `~/.snake/highscore`. Read on start, write on game over if new best. Silent on IO error.
- Show "NEW BEST!" in game-over overlay when record broken.

**Done when:**
1. Speed visibly increases as snake grows.
2. High score survives process restart.
3. "NEW BEST!" shown in overlay on new record.
4. IO error on write silently swallowed.

**Depends on:** ARCH-07, ARCH-10.

---

### ARCH-12 — Updated README

**Why:** README still says "just download SnakeGame.jar" and "close and re-open to restart" — both wrong after this work.

**What:**
- Replace build instructions with `mvn package` + `java -jar target/SnakeGame.jar`.
- Document controls: arrows + WASD, Escape = pause, R = restart, M = menu.
- Add screenshot from the completed game-over screen.
- Update Docker section to reflect build-from-source.

**Done when:**
1. README accurately describes Maven build flow.
2. All controls documented.
3. At least one screenshot of the modernized game included.

**Depends on:** ARCH-07.

---

## Quality gates — every PR must pass

| Check | Enforcement |
|---|---|
| `mvn verify` exits 0 | GitHub Actions CI (ARCH-01) |
| Zero new compiler warnings | Code reviewer |
| `repaint()` only called from EDT | Code reviewer |
| Game launches: `java -jar target/SnakeGame.jar` | Stated in every PR's "Done when" |
| ARCH-10+: all tests pass | `mvn test` in CI |

**Branch naming:** `feat/<desc>`, `fix/<desc>`, `docs/<desc>`
**Commit style:** imperative subject ≤72 chars — e.g. `fix: replace stopTheGame loop with timer.stop()`
**Merge:** squash merge to `master`

---

## UI/UX specification

### Color palette

| Role | Hex | Usage |
|---|---|---|
| Background | `#0F0F23` | Window, all panels |
| Grid line | `#1A1A3A` | 1px lines between cells |
| HUD bar | `#0A0A1A` | Score bar background |
| Snake head | `#00FF87` | Head cell fill |
| Snake body | `#00C9A7` | Body cells fill |
| Food | `#FF6B6B` | Food circle fill |
| Head border | `#80FFB8` | 1px outline on head cell |
| Text primary | `#E0E0E0` | Labels, scores |
| Text secondary | `#8888AA` | Subtitles, hints |
| Accent | `#4ECCA3` | Buttons, best score, "NEW BEST!" |
| Overlay | `rgba(15,15,35,0.85)` | Game-over and pause overlays |

### Typography

- All text: `Monospaced` system font (guaranteed available, no bundling needed).
- Menu title "SNAKE": 64pt bold, `#4ECCA3`.
- "GAME OVER" / "PAUSED": 48pt bold.
- Score in HUD: 16pt bold.
- Score on game-over panel: 22pt bold.
- Buttons: 16pt medium.
- Minimum anywhere: **14pt**.

### Cell rendering (ARCH-03 detail)

Enable anti-aliasing: `g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)`

- **Empty cell:** fill background color, draw grid lines only.
- **Snake body:** `g2.fillRoundRect(x+2, y+2, cellW-4, cellH-4, 8, 8)` in `#00C9A7`.
- **Snake head:** same shape in `#00FF87` + `g2.drawRoundRect(x+2, y+2, cellW-4, cellH-4, 8, 8)` border in `#80FFB8`.
- **Food:** `g2.fillOval(x+4, y+4, cellW-8, cellH-8)` in `#FF6B6B`.

### Screen layouts

**Menu:**
```
┌──────────────────────────┐
│                          │
│         SNAKE            │  64pt bold, #4ECCA3
│  Use arrows or WASD      │  14pt, #8888AA
│                          │
│       [ PLAY ]           │  180×50, bg #4ECCA3, text #0F0F23, r=8px
│                          │
│      BEST: 0             │  16pt, #8888AA
│                          │
└──────────────────────────┘
```

**Game (playing):**
```
┌──────────────────────────┐
│ SCORE  0042   BEST  0099 │  40px HUD, #0A0A1A
├──────────────────────────┤
│  dark grid #0F0F23       │
│  · · ●●●▶ · · ○ · · ·   │  snake: rounded rects; food: circle
│  · · · · · · · · · · ·  │
│              ESC pause   │  hint fades after 5 seconds
└──────────────────────────┘
```

**Pause overlay:**
```
overlay (85% opacity) over frozen game
         PAUSED            48pt bold, #E0E0E0
   Press ESC to resume     14pt, #8888AA
         [ MENU ]          button
```

**Game over overlay:**
```
overlay (85% opacity) over frozen game
       GAME OVER           48pt bold, #FF6B6B
      SCORE  0042          22pt, #E0E0E0
      BEST   0099          22pt, #4ECCA3  (or "NEW BEST!" if record)
  [ PLAY AGAIN ]  [ MENU ] buttons
   R to restart  M or ESC  14pt hint
```

### Accessibility

- All buttons focusable by Tab; activated by Enter/Space.
- Focus indicator: 2px `#4ECCA3` outline on focused button.
- Food is a different **shape** (circle) from snake cells (rounded rect) — not color alone.
- Minimum contrast 4.5:1 for all text (WCAG AA).
- Window resizable; minimum 440×500; grid scales proportionally.

---

## Assumptions

| # | Assumption |
|---|---|
| 1 | FlatLaf 3.4 (Apache 2.0) license is acceptable. |
| 2 | Java 17 is the target runtime — ARCH-02 updates `pom.xml` to match the Dockerfile. |
| 3 | `Monospaced` system font used everywhere — no font bundling needed, no license concern. |
| 4 | Sound effects are out of scope. Silence is preferable to low-quality audio. |
| 5 | High score file write failures are silently ignored — no user notification. |
| 6 | BUG-4 (food x/y axis swap at `ThreadsController.java:62`) works by coincidence on the 20×20 symmetric grid. It is left in place until ARCH-10 tests prove it is safe to fix. |
| 7 | The commented-out second-snake code in `Window.java` is dropped without replacement. |
