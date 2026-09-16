# GoViet — Engine gõ tiếng Việt Telex cho Android

Bộ gõ tiếng Việt Telex trên Android. Hot path không cấp phát String, kiểm tra âm tiết bằng luật chính tả, tương thích hành vi UniKey.

## Kiến trúc engine

```
KeyEvent
  → Composer (VietnameseComposer)
  → OwnedBuffer (zero-alloc)
  → ImeInputConnectionController
  → Android InputConnection
```

### Nguyên tắc thiết kế

- **Không cấp phát String trên hot path.** Mỗi phím bấm đi qua `OwnedBuffer` (CharArray tái sử dụng, implements `CharSequence`). String chỉ được tạo một lần duy nhất khi gọi `setComposingText()` tại biên giới `InputConnection`.
- **Nguồn sự thật duy nhất cho trạng thái âm tiết.** `VietnameseComposer.SyllableState` giữ onset/nucleus/coda/tone. Hiển thị được sinh ra khi cần — không có trạng thái trùng lặp giữa engine và renderer.
- **Kiểm tra âm tiết bằng luật, không lookup từ điển.** Bảng tiền tố âm tiết được sinh tại init từ luật onset tiếng Việt + nuclei/codas trong RimeMap. Không dùng bảng 2700 chuỗi cứng.
- **Tra cứu O(1) bằng flat table.** RimeMap và OnsetMap dùng Fibonacci-hash open-addressing (16384 / 2048 ô) với đóng gói ký tự 5-bit. Không `HashMap`, không boxing, không GC pressure.

## Các thành phần chính

### RimeMap (`RimeMap.kt`)

Mã hóa mọi rime tiếng Việt hợp lệ (nucleus + coda) thành khóa integer 25-bit:

```
key = (charIndex(a) << 10) | (charIndex(b) << 5) | charIndex(c)
```

Giá trị lưu:
- **Vị trí dấu** — ký tự nào trong nucleus chịu dấu thanh
- **Coda fold target** — ánh xạ ký tự coda nhập sang dạng chuẩn (ví dụ `c` → `c`, `ch` → `h`)

Cũng sinh bảng tiền tố âm tiết khi init:

```
OnsetMap.ALL_ONSETS × RimeMap.NUCLEI → rime hợp lệ theo onset → đóng tiền tố → _sylTable
```

### OnsetMap (`OnsetMap.kt`)

Kiểm tra onset tiếng Việt O(1). Cùng kiến trúc Fibonacci-hash. Xử lý quy tắc nguyên âm trước/sau cho `c/k/q` và `g/gh` / `ng/ngh`:

| Onset | Nguyên âm đầu được phép |
|-------|------------------------|
| `c` | a, ă, â, o, ô, ơ, u, ư |
| `k` | e, ê, i, y |
| `g` | a, ă, â, o, ô, ơ, u, ư |
| `gh` | e, ê, i |
| `ng` | a, ă, â, o, ô, ơ, u, ư |
| `ngh` | e, ê, i |
| `qu` | u (onset đóng gói, không phải q+u) |
| `gi` | đặc biệt: nucleus co lại khi bắt đầu bằng i |

### VietnameseComposer (`VietnameseComposer.kt`)

Engine Telex phân đoạn lại bằng một hàm duy nhất. Mọi phân tách âm tiết đều bắt nguồn từ `resegment()`.

**Luồng phím bấm:**
1. `insertComposingKey()` thêm ký tự thô vào buffer phiên
2. `resegment()` tách buffer thô thành các biên âm tiết
3. `compileRaw()` render từng âm tiết qua `SyllableState.toDisplayBuffer()`
4. Chuỗi hiển thị được đẩy tới `InputConnection` — đúng 1 lần cấp phát

**Xử lý thanh điệu:**
- Phím thanh (`s`/`f`/`r`/`x`/`j`) áp dụng lên đúng ký tự nucleus theo quy tắc chính tả
- `determineTonePosition()` xác định vị trí dấu dựa trên dữ liệu RimeMap
- Onset `qu`: thanh bỏ qua `u` (thuộc onset, không phải nucleus)
- Onset `gi`: thanh hoãn sau quy tắc co nucleus đặc biệt

### OwnedBuffer (`OwnedBuffer.kt`)

Buffer ký tự tái sử dụng, backed by `CharArray`, implements `CharSequence` — loại bỏ cấp phát String mỗi phím bấm:

- `append(Char)` / `append(CharSequence)` — không tạo String trung gian
- `toStringVal()` — đúng 1 lần cấp phát khi `InputConnection.setComposingText()` yêu cầu
- So sánh zero-copy qua `displayPrefixMatches()` trong path theo dõi con trỏ

### ImeInputConnectionController (`ImeInputConnectionController.kt`)

Cầu nối giữa engine và framework IME Android:

- **Theo dõi con trỏ** — `displayCursorIndex()` render tiền tố thô vào buffer, trả về độ dài (zero-alloc)
- **Xử lý xóa** — `rawIndexOfDisplay()` so sánh qua buffer, không substring + casing từng bước
- **Phát hiện loại input** — tự chuyển sang Latin cho trường mật khẩu

### VietnameseUnicode (`VietnameseUnicode.kt`)

Xử lý Unicode tiếng Việt đã precompose:

- `applyTone(char, tone)` → ký tự NFC (ví dụ `a` + `ACUTE` → `á`)
- `stripDiacritics(char)` → ký tự ASCII gốc
- `normalizeIfNeeded()` → NFC normalization chỉ khi có dấu kết hợp

### IntFlatTable (`IntFlatTable.kt`)

Bảng hash generic `Int → Int` open-addressing:

- Hàm băm Fibonacci-multiply
- Linear probing với tái sử dụng tombstone
- Kích thước lũy thừa 2 để chia nhanh bằng bit mask

## Chế độ nhập

| Chế độ | Mô tả |
|--------|--------|
| **Vietnamese Telex** | Telex chuẩn: `a` + `s` → `á`, `d` + `d` → `đ` |
| **Simple Telex** | Biến thể đơn giản hóa |
| **DirectW** | `w` gõ ký tự `w` thay vì biến đổi `ư` |
| **Old Tone Placement** | Quy tắc Bộ Giáo dục 1984 (dấu đặt trên ký tự nucleus cuối) |

## Build

```bash
./gradlew assembleDebug
```

## Giấy phép

AGPL-3.0 — xem [LICENSE](LICENSE). Mã nguồn phải mở cho mọi hình thức triển khai, bao gồm SaaS. Bắt buộc ghi rõ nguồn.
