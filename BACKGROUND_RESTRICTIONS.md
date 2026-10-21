# Monik — керування фоновою роботою застосунків

Оновлено 2026-10-21. Пристрій для перевірки: Android 16 (ColorOS),
без root, через Shizuku (shell UID 2000).

## 1. Чого не вистачало до оновлення

Порівняно з процедурою, яку робили для WeChat (`com.tencent.mm`):

| Крок процедури WeChat | Було в Monik |
|---|---|
| `cmd appops set ... RUN_ANY/RUN_IN_BACKGROUND ignore` | ✅ було |
| `am force-stop` | ✅ було |
| `dumpsys deviceidle whitelist -<pkg>` (прибрати виняток батареї) | ❌ не було |
| `am get-standby-bucket` / `am set-standby-bucket` | ❌ не було |
| Показ ефективного bucket (5/10/20/45) | ❌ було фейкове `10` |
| Список застосунків, навіть коли процес убитий | ❌ «Ресурси» будувалися лише з живих процесів |
| `cmd package unstop` | ❌ не було |
| Статус Shizuku | ❌ не було |

Головне: без показу/зняття `deviceidle whitelist` застосунок міг показувати
«Фон: 🚫», але система все одно тримала бакет `5 = EXEMPTED` і ігнорувала
обмеження. Саме це було коренем проблеми з WeChat.

## 2. Що додано

- **Індикатори в «Ресурси»**:
  - `Фон: ✅/🚫` — `RUN_ANY_IN_BACKGROUND` + `RUN_IN_BACKGROUND`;
  - `Сон: ✅/🚫` — `AUTO_REVOKE_PERMISSIONS_IF_UNUSED`;
  - `WL: ✅/🚫` — виняток оптимізації батареї (`✅ sys` — системний);
  - `Bucket: N` — реальний standby bucket прямо в рядку `Фон/Сон/WL`;
  - `CPU / RAM` — тільки для живих процесів, інакше «процес не запущено».
- **«Обрані»** — це всі додані в моніторинг застосунки, незалежно від того,
  запущені вони чи вбиті. Сортування за назвою, щоб рядки не стрибали.
- **«Обмежити повністю»** (кнопка 🚫, з підтвердженням):
  1. `dumpsys deviceidle whitelist -<pkg>`
  2. `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`
  3. `cmd appops set <pkg> RUN_IN_BACKGROUND ignore`
  4. `cmd appops set <pkg> AUTO_REVOKE_PERMISSIONS_IF_UNUSED default`
  5. `am set-standby-bucket <pkg> restricted`
  6. `am force-stop <pkg>`
- **«Дозволити»** (кнопка ✅, з підтвердженням):
  1. AppOps `allow` для обох операцій;
  2. `AUTO_REVOKE... ignore`;
  3. `am set-standby-bucket <pkg> active`;
  4. `dumpsys deviceidle whitelist +<pkg>` (додає в whitelist — за бажанням користувача);
  5. `cmd package unstop --user 0 <pkg>`.
- **Підтвердження** для швидких кнопок 🚫/✅, щоб випадковий тап не змінив
  банківський застосунок.
- **Статус Shizuku** у «Налаштуваннях» + кнопка відкрити Shizuku.
- **Мова інтерфейсу**: «Налаштування» → «Мова додатку» → «Українська» / «English». Вибір зберігається між запусками; після зміни застосунок залишається на екрані налаштувань. На Android 13+ мова також видна в системних налаштуваннях застосунку.
- **«Ресурси» працюють без дозволу Usage Stats** (він потрібен лише «Таблиці»).

## 2.1. Позначення індикаторів

| Індикатор | Зелений | Червоний |
|---|---|---|
| `Фон` | ✅ дозволено | 🚫 заборонено |
| `Сон` | ✅ не присипляє | 🚫 може присипити |
| `WL` | ✅ є виняток (user/sys) | 🚫 винятку немає |
| `Bucket` | 5 EXEMPTED, 10 ACTIVE | 40 RARE, 45 RESTRICTED |

`WL: 🚫` **не означає, що застосунок одразу закриється**. Це означає, що в нього
немає імунітету від оптимізації батареї. Остаточно все вирішують `Фон` і `Bucket`:
наприклад, `WL: 🚫` + `Фон: ✅` + `Bucket: 10` — застосунок може працювати у фоні.
А `Фон: 🚫` + `Bucket: 45` — не буде.

## 3. Як користуватися

1. Переконайтеся, що Shizuku запущений і Monik має дозвіл
   («Налаштування» → «Shizuku: ✅ працює, дозвіл є»).
2. «Таблиця» → пошук → «Додати» потрібний застосунок.
3. «Ресурси» → «Обрані».
4. Дивіться індикатори. `WL: 🚫` — це лише відсутність винятку батареї;
   сам по собі він не вбиває застосунок. Дивіться також `Фон` і `Bucket`.
5. 🚫 — обмежити повністю; ✅ — дозволити (з whitelist); 🥾 — force-stop.

## 4. Перевірено на пристрої

На тестовому застосунку `com.example.testapp`:

| Стан | Bucket | WL | AppOps | Процес |
|---|---|---|---|---|
| До | 5 (EXEMPTED) | ✅ user | allow | не запущений |
| Після «Обмежити» | 45 (RESTRICTED) | 🚫 | ignore | 0 |
| Після «Дозволити» | 5 (EXEMPTED) | ✅ user | allow | не запущений |

Також перевірено force-stop запущеного застосунку: після дії `ps` не бачить
процесу, а рядок у «Обрані» залишається зі станом «процес не запущено».

Під час повторного тесту ColorOS спочатку виставив bucket `10`, потім `45`;
тому Monik показує саме ефективне значення, а не те, яке запросили.

WeChat (`com.tencent.mm`) **не тестували** — щоб не ризикувати баном.

## 5. Обмеження та застереження

- ColorOS може перевизначати `restricted` на `10` або `20` (як було з WeChat).
  Monik показує **ефективний** bucket і попереджає, якщо він не `45`.
- ColorOS може повторно додати застосунок у whitelist після ребуту/оновлення —
  тоді процедуру треба повторити.
- Пуші/повідомлення обмеженого застосунку, скоріш за все, перестануть
  приходити — це прямий наслідок мети.
- Потрібен запущений Shizuku. Без нього команди не виконуються (Monik покаже
  це у «Налаштуваннях»).
- `dumpsys deviceidle whitelist` розрізняє `user` і `system` винятки. Monik
  прибирає `user`; системні (`sys`) записи змінити без root не можна.

## 6. Відкат

```bash
adb shell 'dumpsys deviceidle whitelist +<pkg>'
adb shell 'cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow'
adb shell 'cmd appops set <pkg> RUN_IN_BACKGROUND allow'
adb shell 'cmd appops set <pkg> AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore'
adb shell 'am set-standby-bucket <pkg> active'
adb shell 'cmd package unstop --user 0 <pkg>'
```

## 7. Ключові файли

- `app/src/main/java/com/example/monik/AppManager.java` — збір станів, команди,
  кеш, bucket, whitelist.
- `app/src/main/java/com/example/monik/MainActivity.java` — підтвердження дій,
  статус Shizuku.
- `app/src/main/java/com/example/monik/AppAdapters.java` — індикатори рядків.
- `app/src/main/res/layout/item_resource_row.xml` — окремий layout «Ресурсів».
- `app/src/main/res/values/strings.xml` — українські рядки (типові).
- `app/src/main/res/values-en/strings.xml` — англійські рядки.
- `app/src/main/res/xml/locales_config.xml` — перелік мов для Android 13+.
- `app/build/outputs/apk/debug/app-debug.apk` — зібраний APK.
