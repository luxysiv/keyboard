# GoViet — Bàn phím tiếng Việt Telex

## Luồng gõ

```
 KeyEvent → Composer → OwnedBuffer → setComposingText
              │
              ▼
          resegment()
         ┌────┴────┐
      matchOnset   scanBody     → toDisplay
      (longest     (gõ từng     (suy ra ký
       prefix)     phím theo    tự hiển thị
                   nhóm)        từ buffer thô)
```

## Fold Telex

```
 a+a → ă    a+w → ă    e+e → ê    o+o → ô
 o+w → ơ    u+w → ư    w          → ư (directW tắt)
```

Gõ lại cùng phím = untoggle (a+a+a → a).

## Dấu

```
 "luan" + s → "luân"    "luân" + s → "luan"
 "qu" + a + s → "quá"   "gi" + a → "gia"  (+ s → "giá")
```

## Onset rules

```
 c, g, ng  → back vowels  (a,ă,â,o,ô,ơ,u,ư)
 k, gh, ngh → front vowels (e,ê,i,y)
 qu        → u thuộc onset
 gi        → nucleus co lại khi i theo sau
```

## Coda & deferred fold

```
 "tuan" + a → "tuân"     "tuan" + a + a → "tuanna"
 "luan" + s + a → "luân a"
```

## Backspace

Buffer thô giữ nguyên, resegment lại — không mất dấu.

## Build

```bash
./gradlew assembleDebug
```

## Giấy phép

AGPL-3.0 — [LICENSE](LICENSE). Bắt buộc ghi rõ nguồn.
