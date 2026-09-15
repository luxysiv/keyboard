# GoViet Engine Improvement Analysis

## Nghiên cứu các bộ gõ tiếng Việt khác & đề xuất cải tiến

---

## 1. Tổng quan các bộ gõ đã nghiên cứu

| Dự án | Nền tảng | Phương pháp | Stars | Điểm nổi bật |
|-------|----------|-------------|-------|-------------|
| **Viet+ (VietC)** | Linux (Rust) | Telex + VNI | 61 | Zero Underline, auto English recovery |
| **v7** | Win/Mac (Python) | AI prediction | 102 | Gõ consonant+tone → predict word |
| **vi-rs** | Library (Rust) | Telex/VNI | 158 | Minimal, embeddable library |
| **bogoengine** | Linux (C++) | Modular engine | 26 | Clean engine/UI separation |
| **libunikey** | Cross-platform (C++) | Telex + Envi | — | Envi mode for English/Vietnamese mix |
| **Lex** | macOS (Zig) | Custom Telex | 4 | Old-style tone placement option |
| **AVIM** | Browser (JS) | Telex/VNI/VIQR | 47 | Multi-input-method support |
| **Funput** | iOS/Android/Desktop | Telex/VNI | 30 | Cross-platform consistency |
| **Sophia** | Android | Smart prediction | 14 | On-device word prediction |

---

## 2. Phân tích strengths của GoViet engine hiện tại

Engine GoViet đã rất xuất sắc ở:

- **Zero-alloc design**: `OwnedBuffer`, `IntFlatTable`, primitive arrays — GC pressure gần như bằng 0
- **Flat-table rime map**: 25-bit packed key, Fibonacci hash, O(1) lookup — nhanh hơn nhiều engine dùng hash map
- **Single-resegment architecture**: `resegment()` là single source of truth — rất elegantly compact
- **Grapheme-aware backspace**: Delete theo grapheme cluster, không theo raw keystroke
- **Adopt + round-trip gate**: Có thể "nhận" text đã commit và quay lại editing mode — tính năng cao cấp
- **Macro system**: Hỗ trợ macro expand khi commit
- **BoundaryClassifier**: Phân loại whitespace/word separator/sentence terminator — tốt cho auto-shift

---

## 3. Khoảng trống cần cải tiến (so với các engine khác)

### 3.1 🧠 AI Word Prediction (lấy cảm hứng từ v7)

**Vấn đề**: Gõ tiếng Việt hiện tại vẫn requires 5-8 keystrokes cho mỗi syllable. Ví dụ: "tưởng tượng" = "tuongwr tuongwj" (18 keystrokes).

**Giải pháp từ v7**: Chỉ cần consonant + tone number, AI sẽ predict toàn bộ word:
- `t3t5` → "tưởng tượng" (thay vì 18 keystrokes)
- `x0ch2` → "xin chào"

**Đề xuất cho GoViet**:
```
Priority: MEDIUM-HIGH
Effort: HIGH (cần on-device model, ~50MB)
```

- Tích hợp lightweight n-gram model (UniGram/BiGram) cho Vietnamese
- Store dictionary compact: prefix tree (trie) với frequency data
- Hold key để show candidate list (như Gboard)
- Ngôn ngữ gợi ý mỗi khi user pause giữa word

### 3.2 🔄 Auto English Recovery (lấy cảm hứng từ Viet+)

**Vấn đề**: Khi gõ tiếng Anh giữa chừng (brand names, technical terms), engine hiện tại không tự detect và giữ nguyên.

**Giải pháp từ Viet+**:
- Phonology analysis: nếu word "sounds Vietnamese" → apply Telex
- Nếu không → giữ nguyên English

**Đề xuất cho GoViet**:
```
Priority: HIGH
Effort: LOW (dictionary-based check)
```

Thêm `AutoEnglishDetector`:
```kotlin
object AutoEnglishDetector {
    // Words that exist in English but NOT Vietnamese
    private val englishDictionary: Set<String> = loadBundledSet("english_common.txt")
    
    // Words that look Vietnamese (onset + valid rime)
    fun looksVietnamese(word: String): Boolean {
        val stripped = VietnameseUnicode.stripDiacritics(word.lowercase())
        val onsetLen = OnsetMap.longestOnsetPrefix(stripped)
        val rime = stripped.substring(onsetLen)
        return RimeMap.isValidPrefix(RimeMap.rimeKey(rime))
    }
    
    fun shouldAutoSwitch(word: String): Boolean {
        val lower = word.lowercase()
        if (lower in englishDictionary) return true
        if (!looksVietnamese(lower)) return true
        return false
    }
}
```

### 3.3 🎯 Candidate Bar / Suggestion Strip

**Vấn đề**: Hiện tại engine chỉ render 1 result. Không có candidate list cho ambiguous inputs.

**Đề xuất cho GoViet**:
```
Priority: HIGH
Effort: MEDIUM
```

- Hiển thị 3-5 candidates khi input ambiguous
- Candidates reorder dựa trên frequency
- Tap candidate để select thay vì press space
- Thêm inline suggestion (giống Gboard "next word suggestion")

### 3.4 📊 VNI Input Method Support

**Vấn đề**: GoViet chỉ hỗ trợ Telex. Nhiều người dùng quen VNI hơn (đặc biệt miền Nam).

**Đề xuất cho GoViet**:
```
Priority: HIGH
Effort: MEDIUM (thêm input mapper, core logic shared)
```

VNI key map:
```
1 = sắc (á)
2 = huyền (à)
3 = hỏi (ả)
4 = ngã (ã)
5 = nặng (ạ)
6 =.entering acute (ắ) 
7 =.entering grave (ằ)
0 = no tone
```

- Core engine (resegment, rime map) giữ nguyên — chỉ thay key mapper
- User có thể switch Telex ↔ VNI ↔ Envi trong settings
- Kiểu gõ hiện tại (`processKey`) chỉ cần thêm `VniKeyMapper`

### 3.5 🌊 Multi-syllable Composing Buffer

**Vấn đề**: Mỗi syllable được commit riêng lẻ. Không thể compose phrase "xin chào" liên tục.

**Đề xuất cho GoViet**:
```
Priority: MEDIUM
Effort: MEDIUM
```

- Giới hạn composing buffer max length (ví dụ 80 chars)
- Word boundary detection vẫn commit khi space/enter
- Nhưng cho phép cursor di chuyển giữa words trong composing
- Nhập "xincha" rồi navigate để edit "xin" trước khi commit

### 3.6 📝 Smart Space Handling

**Vấn đề**: Space handling hiện tại là hard commit. Không có smart behavior.

**Đề xuất cho GoViet**:
```
Priority: HIGH
Effort: LOW
```

- Double space → period + space (như iOS keyboard)
- Space after punctuation → auto capitalize next letter
- Space after abbreviation ("ts.", "bs.", "tp.") → lowercase next word
- No space needed before punctuation (auto-remove trailing space)

### 3.7 🔄 Undo/Redo Support

**Vấn đề**: Không có undo/redo khi typing. Backspace chỉ delete grapheme.

**Đề xuất cho GoViet**:
```
Priority: MEDIUM
Effort: LOW-MEDIUM
```

- Maintain action history stack (last N actions)
- Swipe left on keyboard → undo
- Swipe right → redo (if available)
- Long-press backspace → delete word (hiện đã có phần)

### 3.8 🎨 Touch-based Swipe Input (Swype/Glide)

**Vấn đề**: Không có swipe typing. Chỉ tap-based.

**Đề xuất cho GoViet**:
```
Priority: LOW-MEDIUM (nice-to-have)
Effort: HIGH (cần gesture recognition + path-to-word algorithm)
```

- Enable swipe gesture on keyboard layout
- Path-to-candidates using spatial model (n-gram probabilities)
- Confidence threshold cho best candidate

### 3.9 📱 Haptic Feedback & Sound Design

**Vấn đề**: Haptic feedback hiện tại cơ bản. Không có audio feedback design.

**Đề xuất cho GoViet**:
```
Priority: LOW
Effort: LOW
```

- Different haptic intensity per key type (letter, space, backspace)
- Key press sound effect (optional)
- Error feedback (distinctive vibration pattern)

### 3.10 🧹 Smart Punctuation & Formatting

**Vấn đề**: Không có auto-formatting hoặc smart quotes.

**Đề xuất cho GoViet**:
```
Priority: MEDIUM
Effort: LOW
```

- Auto-capitalize first letter after sentence
- Auto-capitalize after name (e.g., "Ông", "Bà")
- Smart quotes: " → « » (Vietnamese standard)
- Auto-close brackets
- Auto format phone numbers: 0901234567 → 090 123 456

### 3.11 🔀 Envi Input Method (lấy cảm hứng từ libunikey)

**Vấn đề**: Telex có conflict với English (w, f, j, s, r, x, d có ý nghĩa trong English).

**Giải pháp từ libunikey Envi mode**:
- Dùng ph很少 dùng trong English bigrams cho Vietnamese tone:
  - Q = sắc, X = ngã, J = nặng, Z = huyền, B = hỏi
- Double-letter suffix cho special vowels: aa=â, aw=ă, ee=ê, oo=ô, ol=ơ, uu=ư

**Đề xuất cho GoViet**:
```
Priority: MEDIUM (optional mode)
Effort: MEDIUM
```

- Thêm Envi mode vào input method selector
- Kích hoạt bằng toggle button hoặc long-press shift

### 3.12 📊 Analytics & Learning

**Vấn đề**: Không có user behavior tracking để improve suggestions.

**Đề xuất cho GoViet**:
```
Priority: LOW
Effort: MEDIUM
```

- Track word frequency locally (on-device)
- Adapt suggestions based on user typing patterns
- Never send data to server (privacy-first)
- Store in local SQLite or Realm

---

## 4. Ưu tiên triển khai (Recommended Roadmap)

### Phase 1 (Next Release): Quick Wins
1. **Smart Space** (3.6) — 1-2 ngày
2. **Auto English Detection** (3.2) — 2-3 ngày
3. **Smart Punctuation** (3.10) — 1 ngày
4. **Undo/Redo** (3.7) — 2-3 ngày

### Phase 2 (Medium-term): Core Engine
5. **VNI Mode** (3.4) — 3-5 ngày
6. **Candidate Bar** (3.3) — 5-7 ngày
7. **Envi Mode** (3.11) — 3-4 ngày

### Phase 3 (Long-term): Advanced
8. **Word Prediction / AI** (3.1) — 2-3 tuần
9. **Swipe Typing** (3.8) — 2-3 tuần
10. **User Analytics** (3.12) — 1 tuần

---

## 5. Technical Architecture Notes

###现有引擎模块化得很好:
```
VietnameseComposer  ← Core logic (unchanged for most improvements)
├── RimeMap         ← Flat table rime lookup
├── OnsetMap        ← Onset validation
├── VietnameseUnicode ← Tone tables
├── ImeInputConnectionController ← IME layer
│   └── BackspaceHandler
└── EngineOptions   ← Configuration

New modules to add:
├── VniKeyMapper     ← New input method adapter
├── EnviKeyMapper    ← New input method adapter
├── AutoEnglishDetector ← English/phonology analysis
├── CandidateBar     ← Suggestion UI
├── WordPredictor    ← n-gram based prediction
├── UndoRedoManager  ← Action history
└── SmartFormatter   ← Auto-formatting rules
```

### Key Design Principle Preservation:
- **Zero-alloc hot path**: Tất cả prediction data should use pre-allocated buffers
- **Primitive arrays**: Avoid boxed collections in hot path
- **Thread-local buffers**: Like OwnedBuffer pattern

---

## 6. Competitor-Specific Insights

### Viet+ (VietC) Key Insight:
- "Zero Underline" concept — gõ trực tiếp vào app, không dùng pre-edit buffer
- **Áp dụng cho GoViet**: Có thể implement "fast commit" mode — commit ngay mỗi syllable thay vì maintain composing buffer

### v7 Key Insight:
- 8-tone system (tách biệt entering tones) giúp prediction accuracy cao hơn
- Even partial input (just consonant + tone) can predict words
- **Áp dụng cho GoViet**: Có thể dùng 8-tone cho word prediction feature

### libunikey Envi Key Insight:
- Phím tắt English bigram probability để tránh conflicts
- **Áp dụng cho GoViet**: Thêm Envi mode cho users cần mixed English/Vietnamese

### bogoengine Key Insight:
- Rất clean engine/UI separation — engine là pure function
- **Áp dụng cho GoViet**: Đã做得很好了, core engine đã clean

---

*Analysis completed: 2026-09-14*
*References: GitHub research on 12+ Vietnamese IME projects*

---

## 9. UniKey Empirical Behavior Map (built from C++ harness)

The following mapping was verified by building the actual UniKey C++ engine
(xmirror/unikey) and processing each raw Telex sequence through `UkEngine::process`
with `CONV_CHARSET_XUTF8`, `modernStyle=0`, `spellCheckEnabled=1`.

### 9.1 VCPairList Coda Matrix (from ukengine.cpp)

| Nucleus | Valid codas (coda count) | Source VSeq |
|---------|--------------------------|-------------|
| `ua`    | n, ng, t                 | vs_ua       |
| `uâ`    | n, ng, t                 | vs_uar      |
| `uye`   | n, t                     | vs_uye      |
| `uyê`   | n, t                     | vs_uyer     |
| `oa`    | c, ch, m, n, ng, nh, p, t | vs_oa     |
| `oe`    | n, t                     | vs_oe       |
| `uo`    | c, m, n, ng, p, t        | vs_uo       |

### 9.2 Tone Position: Open vs Terminated (UniKey `getTonePosition`)

UniKey computes tone position at OUTPUT time, not at keystroke time. When a
coda is added to an open rime, the tone may MOVE to a different vowel. The
rule for each VowelSeq:

```
getTonePosition(vs, terminated):
  if len==1 → 0
  if roofPos != -1 → roofPos
  if hookPos != -1 → (vs in uh- family → 1 else hookPos)
  if len==3 → 1
  if modernStyle && vs in {oa,oe,uy} → 1
  return terminated ? 0 : 1
```

**GoViet emulation**: The `tnNewCoda` field (added to NucSpec) encodes the
position for the coda'd form. When `tnNewCoda ≠ tnNew`, the coda row in the
flat table uses `tnNewCoda`, reproducing the UniKey terminated/open split.

| Nucleus | Open tnNew | Coda tnNewCoda | Example (open) | Example (coda) |
|---------|------------|----------------|----------------|----------------|
| `ua`    | 0 (u gets tone) | 1 (a gets tone) | chuaf→chùa | churan→chuản |

### 9.3 Empirical Keystroke → Display (verified)

| Raw sequence | Display | Notes |
|---|---|---|
| luan | luan | Plain rime |
| luana | luân | Fold ua→uâ |
| luanaa | luana | Untoggle |
| luyene | luyên | Fold uye→uyê |
| luyenee | luyene | Untoggle |
| xuatas | xuất | Fold + tone |
| chuas | chúa | Open ua + tone |
| churan | chuản | ua + coda + tone on 'a' |
| churana | chuẩn | Fold uâ + tone |
| chuanra | chuẩn | Coda + tone + fold |
| chuaanr | chuẩn | Fold-before + tone |
| chuyeenr | chuyển | Fold-before + tone |
| chuyener | chuyển | Fold-after + tone ✓ |
| bana | bân | Fold a→â |
| banaa | bana | Untoggle |
| banana | bânna | Fold at second 'a' |
| thuown | thươn | uo→ươ via rawOverride "uwo" |
| toas | toá | Open oa + tone (old-style pos 0) |

### 9.4 Fold-Last Canonical (Laban/UniKey continuation behavior)

UniKey and Laban Key allow fold key after coda. When a folded word is committed
and the engine re-adopts the survivor, the canonical raw uses fold-last ordering
so retype untoggles:

- `luyên` committed → adopt → `luyene` → retype `e` → `luyenee` → untoggle → `luyene`
- `luân` committed → adopt → `luana` → retype `a` → `luanaa` → untoggle → `luana`

The fold-last form is: `plainNucleus + coda + foldKey [+ toneKey]`.

Only single-folded nuclei qualify (one folded vowel character: â/ê/ô/ă/ơ/ư).
Multi-fold compounds (ươ = ư+ơ from uo) keep fold-first to preserve the
w-compound tie-break logic (uwo→ươ vs uow→抢险 ambiguity).
