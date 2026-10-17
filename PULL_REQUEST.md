# Pull Request: CaseSystem — плагин + исправления компиляции

**Base:** `main`  ←  **Head:** ветка из патча `casesystem-fixes.patch` (коммиты применяются поверх `bf07157 Initial commit`)

## Title
`feat: CaseSystem plugin (Paper 1.19.2–1.21.1) + fix 8 CI compile errors`

## Body (для описания PR)

```markdown
## Что добавлено
Полный плагин **CaseSystem** для Paper (один JAR на 1.19.2 и 1.21.1):

- `/cs` (`/case`, `/casesystem`) — двухуровневое GUI: список кейсов по правам → детальное GUI
  с иконкой, описанием, кнопкой открытия; опционально (per-case флаг `show-contents`) —
  список редкостей/предметов с процентами выпадения.
- Админ-подкоманды: `/cs reload`, `/cs give <player> <case_id> <amount>`, `/cs list`, `/cs debug`
  + tab-completion.
- Дроп в две ступени: сначала взвешенный рандом по редкостям, затем взвешенный рандом
  по предметам внутри выбранной редкости.
- Анимация из двух фаз (иконки редкостей → предметы внутри редкости):
  - 1.19.4+/1.21.1 — нативный `ItemDisplay` (через рефлексию, чтобы компилироваться против API 1.19.2);
  - 1.19.2 — ProtocolLib: фейковые `SPAWN_ENTITY`/`ENTITY_METADATA`/teleport-follow (3D-предмет);
  - без ProtocolLib / при `type: gui` — плоская GUI-рулетка (fallback).
  Тип и длительность фаз в тиках — в конфиге. Звуки и частицы.
- Выдача наград: команды из конфига, плейсхолдеры `%player% %uuid% %world% %case% %rarity% %amount%`;
  внешние — через `PlaceholderAPI.setPlaceholders()` (не `/papi parse`); неизвестные токены
  остаются как есть; выполнение через `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ...)`.
- Права `casesystem.case.open.<id>.<кол-во>`: decrement через LuckPerms API (User+Node+saveUser),
  нода без числа = ∞, amount=0 → явный deny. Без LuckPerms — счётчик в хранилище
  (JSON или SQLite, тип и имена файлов в конфиге), async-запись, preload на AsyncPlayerPreLoginEvent.
- Ресурспак: только передача URL клиенту при входе (если включено), сам плагин ничего не хостит.
- Все зависимости опциональны (softdepend), плагин деградирует корректно.

## Структура (5 Java-файлов)
- `CaseSystem.java` — главный класс, команды, ресурспак, Messages (nested)
- `CaseManager.java` — модель CaseDef/Rarity/ItemDef (nested), GUI, шансы, GUI-рулетка (nested)
- `DataManager.java` — права, LuckPerms, Storage JSON/SQLite (nested)
- `AnimationManager.java` — версионный адаптер 3D/GUI, ProtocolLib-отправка (nested Anchor/ProtoSender)
- `RewardManager.java` — плейсхолдеры и исполнение команд
- ресурсы: `plugin.yml`, `config.yml` (полный пример), `messages.yml`

## Сборка / CI
- Gradle 8.10.2 (wrapper в репозитории), Temurin 17; все зависимости `compileOnly`, шейдинг не нужен.
- GitHub Actions: push в `main` и теги `v*` → сборка, `upload-artifact` (`build/libs/*.jar`),
  релиз по тегу (`softprops/action-gh-release`).
- `libs/ProtocolLib-5.3.0.jar` закоммичен (репозитории ProtocolLib недоступны из CI);
  в workflow есть шаг-фолбэк скачивания.

## Исправления после прогона CI (8 ошибок compileJava)
1. `CaseManager.java:170` — `Enchantment.UNBREAKING` нет в API 1.19.2 → `Enchantment.MENDING` + `HIDE_ENCHANTS`.
2. `DataManager.java:90` — у LuckPerms `User` нет `hasPermission(String)` → перебор `u.getNodes()`.
3. `DataManager.java:122` — нет `data().remove(Set<Node>)` → цикл `remove(n)`.
4. `DataManager.java:221` — у `OfflinePlayer` нет `hasPermission()` → онлайн: `player.hasPermission`,
   офлайн: дефолт зарегистрированной пермишки.
5. `AnimationManager.java:341/375/387/447` — `PacketContainer` не импортирован во вложенном классе
   → fully-qualified `com.comphenix.protocol.events.PacketContainer`.

Локально: `./gradlew build` → **BUILD SUCCESSFUL**, артефакт `build/libs/CaseSystem-1.0.0.jar`.
```

## Как создать PR (на машине с доступом к UltraSasha/CaseSystem)

### Способ 1 — git am + push (рекомендуется)
```bash
git clone https://github.com/UltraSasha/CaseSystem.git
cd CaseSystem
git checkout main
git checkout -b feature/casesystem-plugin

# casesystem-fixes.patch лежит в корне /workspace (5.9 MB, 2 коммита поверх Initial commit)
git am --keep-cr < /path/to/casesystem-fixes.patch

git push -u origin feature/casesystem-plugin

# если установлен gh CLI:
gh pr create --base main --head feature/casesystem-plugin \
  --title "feat: CaseSystem plugin (Paper 1.19.2-1.21.1) + fix 8 CI compile errors" \
  --body-file PULL_REQUEST.md
```
Или без gh: откройте страницу, которую вернёт `git push` («Create pull request»).

### Способ 2 — прямо из репозитория (без форка)
Ваша служебная ветка ещё не существует на GitHub:
```bash
cd <клон UltraSasha/CaseSystem>
git am --keep-cr < casesystem-fixes.patch
git push origin HEAD:refs/heads/qwen-code-c1809eba
# затем Compare & pull request: base=main, compare=qwen-code-c1809eba
```

### Способ 3 — через GitHub UI (если нет локального git-доступа)
Скачайте `/workspace/casesystem-fixes.patch`, удалите из него бинарные файлы вручную
(или используйте `/workspace/src`, `build.gradle`, `settings.gradle`, `.github/workflows/build.yml`,
`gradlew*`, `gradle/wrapper/*`, `libs/ProtocolLib-5.3.0.jar`) и загрузите в новую ветку
через «Add file → Upload files» → «Commit new branch» → «Contribute → Open pull request».

## Проверка
Патч протестирован: чистый клон `main` + `git am casesystem-fixes.patch` даёт дерево,
байт-в-байт идентичное собранному и успешно прошедшему `./gradlew build`.
