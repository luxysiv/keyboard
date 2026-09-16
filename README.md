# GoViet Keyboard Engine

Vietnamese Telex input method engine for Android. Zero-allocation hot path, rule-based syllable validation, UniKey-compatible behavior.

## Engine Architecture

```
KeyEvent
  → Composer (VietnameseComposer)
  → OwnedBuffer (zero-alloc scratch)
  → ImeInputConnectionController
  → Android InputConnection
```

### Core Design Principles

- **Zero String allocation on the hot path.** Every keystroke goes through `OwnedBuffer` (a reusable `CharArray`-backed `CharSequence`). String is only materialised once via `setComposingText` at the `InputConnection` boundary.
- **Single source of truth for syllable state.** `VietnameseComposer.SyllableState` holds onset/nucleus/coda/tone. Display is derived on demand — no duplicated state between engine and renderer.
- **Rule-based phonotactics, not corpus lookup.** Syllable prefix validation is generated at init from Vietnamese onset rules + RimeMap nuclei/codas. No hardcoded 2700-entry string table.
- **O(1) flat-table lookups.** RimeMap and OnsetMap use Fibonacci-hash open-addressing tables (16384 / 2048 slots) with 5-bit character packing. No `HashMap`, no boxing, no GC pressure.

## Key Components

### RimeMap (`RimeMap.kt`)

Encodes all valid Vietnamese rimes (nucleus + coda) into a packed 25-bit integer key:

```
key = (charIndex(a) << 10) | (charIndex(b) << 5) | charIndex(c)
```

Values encode:
- **tone position** — which character in the nucleus carries the diacritic
- **coda fold target** — maps typed coda character to its canonical form (e.g. `c` → `c`, `ch` → `h`)

Also generates the syllable prefix table at init time:

```
OnsetMap.ALL_ONSETS × RimeMap.NUCLEI → valid rimes per onset → prefix closure → _sylTable
```

### OnsetMap (`OnsetMap.kt`)

Validates Vietnamese onsets in O(1). Same Fibonacci-hash architecture. Handles the `c/k/q` and `g/gh` / `ng/ngh` front/back vowel split rules:

| Onset | Allowed nucleus start |
|-------|-----------------------|
| `c` | a, ă, â, o, ô, ơ, u, ư |
| `k` | e, ê, i, y |
| `g` | a, ă, â, o, ô, ơ, u, ư |
| `gh` | e, ê, i |
| `ng` | a, ă, â, o, ô, ơ, u, ư |
| `ngh` | e, ê, i |
| `qu` | u (packed onset, not q+u) |
| `gi` | special: nucleus collapses when i-initial |

### VietnameseComposer (`VietnameseComposer.kt`)

Single-resegment Telex engine. All syllable segmentation derives from the `resegment()` function.

**Keystroke flow:**
1. `insertComposingKey()` appends raw character to the session buffer
2. `resegment()` splits the raw buffer into syllable boundaries
3. `compileRaw()` renders each syllable via `SyllableState.toDisplayBuffer()`
4. Display string is pushed to `InputConnection` — one allocation total

**Tone handling:**
- Tone key (`s`/`f`/`r`/`x`/`j`) applies to the correct nucleus character based on Vietnamese orthographic rules
- `determineTonePosition()` resolves tone placement per rime using RimeMap data
- `qu` onset: tone skips `u` (it belongs to the onset, not nucleus)
- `gi` onset: tone defers to the nucleus after special collapse rules

### OwnedBuffer (`OwnedBuffer.kt`)

Reusable `CharArray`-backed `CharSequence` that eliminates per-keystroke allocations:

- `append(Char)` / `append(CharSequence)` — no String intermediate
- `toStringVal()` — single allocation only when `InputConnection.setComposingText()` demands it
- Zero-copy comparison via `displayPrefixMatches()` in the cursor tracking path

### ImeInputConnectionController (`ImeInputConnectionController.kt`)

Bridge between engine and Android IME framework:

- **Cursor tracking** — `displayCursorIndex()` renders raw prefix into buffer and returns length (zero-alloc)
- **Backspace replay** — `rawIndexOfDisplay()` compares via buffer comparison, no substring + casing per step
- **Input type detection** — auto-switches to Latin mode for password fields

### VietnameseUnicode (`VietnameseUnicode.kt`)

Precomposed Vietnamese Unicode handling:

- `applyTone(char, tone)` → NFC-composed character (e.g. `a` + `ACUTE` → `á`)
- `stripDiacritics(char)` → base ASCII character
- `normalizeIfNeeded()` → NFC normalization only when combining marks are present

### IntFlatTable (`IntFlatTable.kt`)

Generic open-addressing hash table for `Int → Int` mappings:

- Fibonacci-multiply hash function
- Linear probing with tombstone recycling
- Powers-of-2 sizing for fast modulo via bit mask

## Input Modes

| Mode | Description |
|------|-------------|
| **Vietnamese Telex** | Standard Telex: `a` + `s` → `á`, `d` + `d` → `đ` |
| **Simple Telex** | Simplified variant |
| **DirectW** | `w` types literal `w` instead of `ư`/`ư` transformation |
| **Old Tone Placement** | Vietnamese Ministry of Education 1984 style (tone on last nucleus character) |

## Build

```bash
# Android build
./gradlew assembleDebug

# Engine unit tests (standalone Kotlin)
kotlinc src/engine/*.kt test/*.kt -include-runtime -d test.jar
java -cp test.jar RunAllKt
```

## License

AGPL-3.0 — see [LICENSE](LICENSE). Source must remain open for any deployment, including SaaS. Attribution required.
