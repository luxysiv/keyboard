# GoViet — Bàn phím tiếng Việt Telex cho Android

Engine gõ tiếng Việt dạng Telex: phím gõ thường được lưu vào **buffer thô** (ASCII),
mọi ký tự hiển thị đều được **suy ra lại mỗi lần gõ** — không có trạng thái tăng dần.

## Ý tưởng cốt lõi

```
Người dùng gõ            Buffer thô             Kết quả hiển thị
─────────────────        ─────────────          ─────────────────
l u a n                 "luan"        ──►       luan
l u a n s               "luans"       ──►       luân
l u a n s + backspace   "luan"        ──►       luan      (replay)
l u a n s + backspace
      + a               "luana"       ──►       luân + a  (nhập tiếp)
```

Vì buffer thô không bị "ăn" khi fold, nên:
- backspace trả về **đúng chuỗi thô trước đó**, gõ tiếp vẫn đúng dấu
- gõ lại phím đã fold sẽ untoggle (bỏ dấu)
- macro/hành vi đều tái sinh từ buffer thô

## Vòng đời mỗi phím bấm

```
 Phím bấm (KeyEvent)
    │  ký tự ASCII thô được append vào buffer
    ▼
┌───────────────────────────────────────────────┐
│            resegment(raw)                      │
│                                               │
│  ┌──────────┐   ┌──────────┐   ┌───────────┐ │
│  │ matchOnset│──▶│ scanBody │──▶│ toDisplay │ │
│  │  Phase 1  │   │  Phase 2  │   │  Phase 3  │ │
│  └──────────┘   └──────────┘   └───────────┘ │
│       │              │               │        │
│ onset dài nhất   đi từng ký tự    render dấu  │
│ từ OnsetMap      theo loại phím   đúng vị trí │
└───────────────────────────────────────────────┘
    │
    ▼
 setComposingText(display)   ← chỗ duy nhất tạo String
```

Mỗi lần gõ = **reparse toàn bộ buffer thô** → trạng thái luôn nhất quán.

## Phase 2 — scanBody: mỗi phím thuộc một nhóm

```
                        ┌─────────────────────────────┐
                        │         Ký tự gõ           │
                        └─────────────┬───────────────┘
                                      │
        ┌──────────────┬──────────────┼──────────────┬────────────────┐
        ▼              ▼              ▼              ▼                ▼
   Phím thanh      a/e/o/w        nguyên âm        phụ âm         còn lại
   s f r x j      phím biến đổi    thường        đứng sau nucleus
        │              │              │              │                │
        ▼              ▼              ▼              ▼                ▼
   đặt dấu/untoggle  fold Telex    nối dài        thử làm coda     literal +
   "s"→sắc          a+w=ă, a+a=ă   nucleus        "n","ng"…       lock cứng
   "ss"→bỏ dấu      e+e=ê, o+o=ô   "ua"+mở rộng >> kiểm tra RimeMap
                    u+w=ư, o+w=ơ                     │
                                                     ▼
                                    hợp lệ → thành coda
                                    không → deferred-fold lookahead
                                           ("tuana"→tuân nhờ a)
                                    vẫn không → literal "tuana"
```

## Cơ chế fold (biến đổi Telex)

Phím `a`/`e`/`o`/`w` sau một nucleus sẽ ghép thành nguyên âm đặc biệt.
Gõ lại đúng phím đó ngay sau vị trí fold sẽ **untoggle**:

```
 a + a ──► ă        a + a + a ──► a   (bỏ fold, ra literal)
 a + w ──► ă        a + w + w ──► a
 e + e ──► ê        e + e + e ──► e
 o + o ──► ô        o + o + o ──► o
 o + w ──► ơ        o + w + w ──► o
 u + w ──► ư        u + w + w ──► u
```

Khi `directW` tắt, `w` đứng đầu ở vị trí nguyên âm sẽ thành `ư`:

```
 w ──► ư        w + a ──► ưa        s + w ──► sư        g + i + w ──► giư
```

## Cơ chế dấu

```
 "luan" + s
    │
    ▼
 determineTonePosition() dùng RimeMap tra cứu rime "ua" + coda "n"
    │  → toneIdx = 1 (dấu đặt trên 'a')
    ▼
 applyTone('a', ACUTE) → 'á'
    │
    ▼
 hiển thị "luân"

 Gõ "s" lần nữa  → untoggle, về "luan"
 Gõ "z"           → xóa dấu, về "luan"
```

Quy tắc đặt dấu:
- `qu`: u thuộc onset, dấu đặt trên nguyên âm sau — `qu + a + s` → **quá**
- `gi`: i co vào onset, dấu hoãn tới nucleus thực — `gi + i + s` là dấu giữ, `+ a` → **giá**
- `oldTonePlacement` bật → dấu đặt theo quy ước cũ (nucleus cuối)

## Cơ chế onset

Greedy longest-prefix từ danh sách onset chuẩn:

```
 "ngh" + "e" ──► nghê     "c" + "a" ──► ca
 "gh" + "e" ──► ghe       "k" + "e" ──► ke
 "ng" + "a" ──► nga       "q" + "u" ──► qu
```

Sau onset, phối hợp nguyên âm đầu theo quy tắc chính tả:

```
 c/k:   "c"+back (a,ă,â,o,ô,ơ,u,ư)   "k"+front (e,ê,i,y)
 g/gh:  "g"+back                      "gh"+front
 ng/ngh:"ng"+back                     "ngh"+front
 qu:    q luôn đi với u — "q" là tiền tố hợp lệ
 gi:    chỉ là onset khi có nguyên âm theo sau
```

## Cơ chế coda & deferred fold

Phụ âm đứng sau nucleus được kiểm tra với RimeMap (nucleus × coda có hợp lệ không).
Nếu chưa hợp lệ, engine **nhìn trước** một phím fold để quyết định:

```
 "tuan" + a       ──►  "tuân"    (a fold "uâ" rồi n làm coda)
 "tuan" + a + a   ──►  "tuanna"  (fold đã xong, a sau là literal)
 "chuan" + r + a  ──►  "chuẩn"   (r đặt dấu trước, a fold sau)
 "luan" + s + a   ──►  "luân + a" (s đặt dấu, a thêm literal)
```

## Backspace / replay

```
 "luâna" backspace
    │
    ▼
 xóa 1 ký tự thô trong buffer → "luans" → resegment lại toàn bộ
    │
    ▼
 "luân"  (không bao giờ "luan s" lẫn lộn vì buffer thô là nguồn)
```

## Tra cứu

Mọi tra cứu đều là **flat table O(1)** (hash Fibonacci + open addressing):

```
 RimeMap   : nucleus × coda → vị trí dấu, fold target
 OnsetMap  : onset hợp lệ → fold target (đ → d + d)
 sylTable  : tiền tố âm tiết hợp lệ → isSyllableDisplayPrefixValid
```

Không `HashMap`, không boxing, không cấp phát trung gian trong hot path.

## Tùy chọn

| Tùy chọn | Mặc định | Ảnh hưởng |
|----------|---------|-----------|
| `directW` | tắt | `w` → `ư` như Telex | bật: gõ literal `w` |
| `oldTonePlacement` | tắt | dấu theo quy ước mới | bật: dấu theo QĐ 1984 |
| `macroEnabled` | tắt | bật gõ tắt từ kho macro |

## Build

```bash
./gradlew assembleDebug
```

## Giấy phép

AGPL-3.0 — xem [LICENSE](LICENSE). Bắt buộc ghi rõ nguồn, mã nguồn phải mở.
