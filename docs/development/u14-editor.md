# U1.4: сетка редактора и модальная форма — визуальный gate OPEN

## Источники и геометрия

Контракт: `ui-1.4-parity.md`; baseline `2073d912`:
`SetEditActivity.kt`, `EditCardDialog.kt`, `activity_set_edit.xml`,
`edit_card_dialog.xml`, `set_edit_activity_menu.xml`.
Просмотрены только доказанные editor/card PNG:

- `../redesign-audit/screenshots/p00-followup/tablet/04-own-editor-confirmed.png`:
  2000×1250, toolbar с Back / названием / Save; параметры над сеткой;
  сетка примерно x=355…1645, y=315…1228, pager по сторонам.
- `05-standard-card-form.png` в той же папке: диалог примерно
  x=638…1360, y=332…905; та же сетка видна под затемнением;
  тип → название → изображение / из текста → звук / синтез / запись → действия.
- `18-pre-save-board-with-card.png`: карточка на первой позиции той же сетки,
  обычная текстовая подпись; новая библиотека и недоказанные старые 20/21 не использованы.

Эти pixel bounds — ориентир, **не утверждение dp/density или совпадения нового UI**.
Новый editor: toolbar; компактная строка параметров; основная площадь — сетка;
на широком окне pager/grid/pager примерно 1:4:1, на узком pager по 48 dp;
интервалы 4 dp, обычная 3×3 укладывается без принудительного min96.
Крупный текст / экстремальное число строк или столбцов разрешают прокрутку
обоих направлений. Диалог ограничен шириной 480 dp и доступной высотой,
тело прокручивается отдельно, Done/Cancel вне прокрутки и имеют равные доли ширины.
Подписи остаются текстом; область изображения зарезервирована до получения bitmap.
Иконки векторные локальные, без новой зависимости / icon pack.

## Сохранённые границы

- Публичные `EditorScreen` / `CardEditorScreen` сохраняют trailing nullable
  `onImagesNeeded` из `21bbbeb`. Standalone wrapper имеет собственный provider;
  интегрированный редактор вызывает `CardEditorDialog` напрямую под **единственным**
  `VisibleImageDemand`. Видимые ленивые ячейки и форма регистрируют demand совместно;
  другие страницы / source filesystem / canonical media не читаются.
- Модальная форма не заменяет экран. Восстановленная форма показывает свою страницу,
  а после закрытия выбранная страница сохраняется. В форме есть Tab/focusable поля,
  IME Done снимает фокус, футер остаётся вне scroll body; фокус ячейки имеет рамку.
- Все действия идут через существующие `AuthoringCommand` с explicit owner/capability;
  нового reducer или storage policy нет. `SaveCard` принимает только в draft;
  `Apply` доступен после завершения формы, не вызывает скрыто SaveCard/CancelCard.
  Back отправляет `Close`, а не `ForceDiscard`; dirty/save failure не обходятся.
- Resize / add / reorder / remove page / swap cells используют существующую session;
  metadata и overflow UI не пересобирает. CONTENT определяется kind, не подписью.
  Пустая подпись CONTENT не превращается в EMPTY.
- Дополнительные действия — в меню / вторичных диалогах, не постоянные списки кнопок.
  Retry / конфликт / UNCONFIRMED / cleanup и audio errors видимы; нормальный save
  доступен как stateDescription заголовка и пункт «Состояние черновика».
  Cancelled/failed/completed semantics приложения не изменены.

## Блокер shared contract для primary — не выдавать за готовый тип формы

`EditorForm.Card` не содержит kind; `ChangeCard` не меняет тип;
`AuthoringSession.saveCard()` всегда создаёт CONTENT. Поэтому выбор SPACE/EMPTY
в форме пока показан **недоступным**, с явным указанием на рабочее меню ячейки.
Не применена опасная подмена «CancelCard; SetCell»: она не атомарна,
не сохраняет тип в durable form и обходит нормальный SaveCard contract.

Чтобы закрыть требование типа формы, primary должен согласовать/поручить shared seam:
persisted `Card.kind` с backward-compatible default, отдельную команду изменения kind,
инициализацию из исходной ячейки и атомарный SaveCard для CONTENT/SPACE/EMPTY,
сохранение cell extensions, отмену media и сброс form в той же операции.
Нужны application tests на cancel/resume/autosave/metadata и совместимость старых draft.
Затем UI radio можно привязать к этому полю/команде. Здесь application/domain не менялись.

В `BoardBehavior` также нет отдельного legacy `withoutSpace`: неподтверждённый
checkbox не подменён ни DIRECT, ни LITERAL. Это конкретное отличие для primary,
если checkbox требуется в принятом fixture.

Новых navigation callbacks не понадобилось: root уже закрывает маршрут при
`CLOSED` / `APPLIED`. P13 parent должен подключить новый composed demand к root loader
и проверить системный Back / Android+iOS navigation; root здесь не редактировался.

## Проверка и оставшаяся приёмка

JDK17, offline, один worker:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.*' --max-workers=1 --offline
git diff --check
```

Новые pure tests: размеры обычной / dense / large-font сетки; CONTENT с пустой
подписью и EMPTY с metadata; восстановленная card/grid форма на правильной странице;
векторные toolbar paths. Существующие UI / AuthoringPresentation / image registry tests
не изменены. Результат focused run: **49 tests, 0 failures, 0 errors** (5 новых),
`git diff --check` PASS. Это не Compose instrumentation и не доказательство визуального паритета.

Не запускались full gate, native build, FFmpeg, download, device/install/permissions.
Primary снимает интегрированные Android/iOS phone/tablet screenshots на сопоставимом
fixture/viewport и проверяет large font, IME, keyboard, TalkBack/VoiceOver, ошибки,
появление поздних изображений и доступность footer. Визуального PASS и одобрения нет.
