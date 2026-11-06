# U1.4: сетка редактора и модальная форма — визуальный gate OPEN

## Источники и геометрия

Контракт: `ui-1.4-parity.md`; baseline `2073d912`:
`SetEditActivity.kt`, `EditCardDialog.kt`, `activity_set_edit.xml`,
`edit_card_dialog.xml`, `set_edit_activity_menu.xml`.
Просмотрены только доказанные editor/card PNG:

- `../redesign-audit/screenshots/p00-followup/tablet/04-own-editor-confirmed.png`:
  toolbar с Back / названием / Save; checkbox «Вводить без пробела» над параметрами
  сетки, pager по сторонам.
- `05-standard-card-form.png` в той же папке: диалог поверх той же сетки под затемнением;
  тип → название → изображение / из текста → звук / синтез / запись → действия.
- `18-pre-save-board-with-card.png`: карточка на первой позиции той же сетки,
  обычная текстовая подпись; новая библиотека и недоказанные старые 20/21 не использованы.

Raw PNG всех трёх tablet-референсов — **2560×1600**, подтверждено primary через sips
(phone-референсы — 2400×1080). Ранее просмотренный preview был масштабирован до
2000×1250: его координаты не являются raw PNG bounds. Численные bounds удалены
до actual capture; доказательства совпадения геометрии нового UI здесь нет.
Новый editor: toolbar; checkbox «Вводить без пробела»; компактная строка параметров; основная площадь — сетка;
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

## Тип формы: shared seam подключён, прежний blocker снят

Reviewed dependency `278204c` cherry-picked как `ef705b5`: trailing
`Card.kind: CellKind = CONTENT` с `EncodeDefault.NEVER` и
`AuthoringAction.ChangeCardKind(kind)`. Radio напрямую отражает `form.kind` и
посылает одну ChangeCardKind; целая строка label кликабельна, не менее 48 dp,
с Role.RadioButton и тем же editing-state enabled. Disabled placeholder удалён.

SPACE/EMPTY скрывают label/image/audio/TTS, но UI не очищает поля формы.
Image-demand скрытой формы пуст; demand видимой сетки под диалогом остаётся в общем
provider. Core отдельно удерживает hidden image/audio leases. Возврат к CONTENT
снова показывает поля/изображение. Save/Cancel остаются в футере; UI не требует
recording скрытых полей и не заменяет SaveCard последовательностью CancelCard→SetCell.
Ошибки authoring/audio не скрываются вместе с content-полями.

Atomic SaveCard, old/new compatibility и leases описаны в `u14-card-kind.md`.
Собственных изменений application/domain/storage/tests core поверх dependency нет.

Legacy `withoutSpace` уже отображается codec-ом в `BoardBehavior.inputStyle`:
`LinkaWire.document` читает LITERAL/CARDS, `encodeWire` записывает `withoutSpace`
из того же поля. Checkbox напрямую derived из `inputStyle == LITERAL` и посылает
существующий Details с копией behavior; отдельного persisted поля или UI buffer нет,
DIRECT/COMPOSE, название и описание сохраняются. Label и положение взяты из
`values-ru/strings.xml` / `activity_set_edit.xml` 1.4 и референса 04.

Новых navigation callbacks не понадобилось: root уже закрывает маршрут при
`CLOSED` / `APPLIED`. P13 parent должен подключить новый composed demand к root loader
и проверить системный Back / Android+iOS navigation; root здесь не редактировался.

## Проверка и оставшаяся приёмка

JDK17, offline, один worker:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :shared:jvmTest --tests 'su.linka.pictures.ui.*' \
  --tests '*EditorFormKindSerializationTest' \
  --tests 'su.linka.pictures.application.authoring.*' --max-workers=1 --offline
git diff --check
```

Новые pure tests: размеры обычной / dense / large-font сетки; CONTENT с пустой
подписью и EMPTY с metadata; восстановленная card/grid форма на правильной странице;
векторные toolbar paths; checkbox LITERAL/CARDS с сохранением DIRECT/COMPOSE и details;
visibility полей и image-demand для CONTENT/SPACE/EMPTY с возвратом к CONTENT.
Прежние 50 UI tests и dependency tests не ослаблены, добавлены 2 pure presentation tests.
Результат focused run: **104 tests, 0 failures, 0 errors** (52 UI + 52 dependency core),
`git diff --check` PASS. Это не Compose instrumentation и не доказательство визуального паритета.

Не запускались full gate, native build, FFmpeg, download, device/install/permissions.
Primary снимает интегрированные Android/iOS phone/tablet screenshots на сопоставимом
fixture/viewport и проверяет large font, IME, keyboard, TalkBack/VoiceOver, ошибки,
появление поздних изображений и доступность footer. Визуального PASS и одобрения нет.
