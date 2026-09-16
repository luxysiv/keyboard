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

## Build

```bash
./gradlew assembleDebug
```

## Giấy phép

AGPL-3.0 — [LICENSE](LICENSE). Bắt buộc ghi rõ nguồn.
