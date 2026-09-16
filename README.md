# GoViet — Engine gõ tiếng Việt Telex cho Android

Bộ gõ tiếng Việt trên Android. Hot path zero-allocation, tra cứu O(1) bằng flat table, tương thích hành vi UniKey.

## Luồng xử lý mỗi phím bấm

```
 Phím bấm
    │
    ▼
┌─────────────────────┐
│  ImeInputController  │  InputConnection → KeyEvent
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  VietnameseComposer │  resegment() phân tách âm tiết
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│    OwnedBuffer      │  CharArray tái sử dụng, zero-alloc
└─────────┬───────────┘
          │
          ▼
   setComposingText()     1 lần cấp phát String duy nhất
```

## Cách engine phân tách âm tiết

```
  "luan"
    │
    ▼
┌──────────────────────────────────────────────────┐
│               resegment()                        │
│                                                  │
│  l ──→ onset "l"                                 │
│  u ──→ nucleus + kiểm tra OnsetMap               │
│  a ──→ nucleus (mở rộng)                         │
│  n ──→ coda (kiểm tra RimeMap)                   │
│                                                  │
│  Kết quả: SyllableState {                        │
│    onset = "l", nucleus = "ua", coda = "n"       │
│  }                                               │
└──────────────────────────────────────────────────┘
          │
          ▼
┌──────────────────────────────────────────────────┐
│         determineTonePosition()                  │
│                                                  │
│  nucleus = "ua" → RimeMap tra cứu                │
│  → toneIdx = 1 (dấu đặt trên 'a')               │
│                                                  │
│  Phím 's': applyTone('a', ACUTE) → 'á'           │
│  Hiển thị: "luân"                                │
└──────────────────────────────────────────────────┘
```

## Cách engine xử lý onset đặc biệt

```
  c/k, g/gh, ng/ngh: chọn onset theo nguyên âm đầu

  "c" + "a" → "ca"  ✓   ("c" chấp nhận back vowels)
  "k" + "a" → ✗         ("k" chỉ chấp nhận front vowels)
  "k" + "e" → "ke"  ✓
  "g" + "a" → "ga"  ✓
  "gh" + "e" → "ghe" ✓
  "ngh" + "e" → "nghê" ✓

  qu: onset đóng gói, u thuộc về onset
  q + u + a → "qua"    (u không phải nucleus)
  tone lên 'a': "quá"

  gi: nucleus co lại
  g + i + a → "gia"
  g + i + i + ng → "ging"
```

## Cách engine xử lý backspace

```
  "luân" ← phím xóa
    │
    ▼
┌─────────────────────────────────────┐
│        BackspaceHandler             │
│                                     │
│  1. Xóa ký tự cuối display buffer   │
│  2. Kiểm tra prefix còn hợp lệ?     │
│  3. Nếu hợp lệ → giữ buffer thô     │
│  4. Nếu không → replay từ đầu      │
└─────────────────────────────────────┘
```

## Hệ thống bảng tra cứu

```
┌──────────────────────────────────────────────────┐
│                  RimeMap                         │
│                                                  │
│  Khóa: 25-bit đóng gói (5-bit × 5 ký tự)       │
│  Giá trị: vị trí dấu + coda fold target         │
│  Bảng: 16384 ô, Fibonacci-hash, linear probe    │
│                                                  │
│  "ua" → toneIdx=1, codaFold={}                  │
│  "uân" → toneIdx=1, codaFold={n→n}             │
│  "oa" → toneIdx=1, codaFold={}                  │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│                  OnsetMap                         │
│                                                  │
│  Kiểm tra onset tiếng Việt hợp lệ O(1)          │
│  Bảng: 2048 ô, cùng kiến trúc Fibonacci-hash    │
│                                                  │
│  "ngh" ✓    "nha" ✓    "q" ✓ (tiền tố qu)     │
│  "ngh" + "a" → ✗  (ngh chỉ nhận e/ê/i)         │
│  "c" + "e" → ✗     (c chỉ nhận back vowels)    │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│              Bảng tiền tố âm tiết                │
│                                                  │
│  Sinh tự động tại init:                          │
│  OnsetMap × RimeMap.NUCLEI → tiền tố hợp lệ     │
│                                                  │
│  Dùng để kiểm tra:                              │
│  "luan" có phải tiền tố hợp lệ không?           │
│  → O(1) lookup trong _sylTable                   │
└──────────────────────────────────────────────────┘
```

## Chế độ cài đặt

| Tùy chọn | Mặc định | Mô tả |
|----------|---------|-------|
| `directW` | `false` | `w` → `ư` (tắt: `w` gõ ký tự `w` thưa) |
| `oldTonePlacement` | `false` | Dấu đặt theo QĐ 1984 (trên nucleus cuối) |
| `macroEnabled` | `false` | Bật macro gõ tắt |

## Build

```bash
./gradlew assembleDebug
```

## Giấy phép

AGPL-3.0 — xem [LICENSE](LICENSE). Mã nguồn phải mở cho mọi hình thức triển khai. Bắt buộc ghi rõ nguồn.
