# Визуальный паритет Electron: действующий контракт

Решение пользователя 7 ноября 2026: Electron заменяет визуальный baseline Android
1.4; перенос на телефоны адаптивный. Пользователь поручил выполнить весь C4 coding
plan V00–V15, сверять native screenshots и фиксировать каждый шаг отдельным commit.
Это разрешение реализации, не визуальное одобрение результата и не публикация.

## Эталон и область

`linka.looks-electron` commit `318a969973412e6affb13f24b456cd47b8ea1476`.
Свежий атлас исследования: OpenCode temp `linka-visual-2hPM7Q/atlas.html`, 29 кадров
Electron и три диагностических native кадра. Renderer снят с текущих исходников,
main — существующий dist-electron. Native binary identity/одинаковые fixtures
этими тремя кадрами не доказаны. Исторический desktop report не выше свежего кода.

Общий Compose Material2 UI, без WebView, новых backend/dependencies или изменения
схемы `PortableSettings`. Светлая палитра: canvas F6F7F4, surface FFFFFF, text243431,
muted536761, primary197377, navigationE2EEEA, functional border778C84,
decorative dividerD6E1DB, dangerA33838. Функциональную рамку нельзя заменять divider.
Карточки плоские, contain/Fit, radius3; панели мягче, отступ16, editor gap8.
Подписи — текст с системным × portable scale. После отклонённого пользователем
кадра с крупным текстом внутренние scroll заголовка/подписей запрещены: измеряется
ширина целого слова и полная высота текста. Последнее уточнение для детского UX
отменяет рост сетки с прокруткой: вся страница помещается в один viewport.
Заголовок переносится целиком, не прячет половину строки.
Dark и high contrast сохраняются и проверяются отдельно.

## Адаптация и инварианты

Последующее решение пользователя 7 ноября 2026: весь новый общий UI Android,
iPhone и iPad фиксируется в двух горизонтальных ориентациях, без переключателя.
Портретные кадры остаются историческими доказательствами, а не новым acceptance
baseline. Ограничение задаётся платформенной конфигурацией; PortableSettings,
архивы наборов и production Android admission не меняются. Системные диалоги и
оконный режим новых iPadOS проверяются отдельно, не считаются автоматически
подконтрольными приложению.

- Размер доступного окна, не device-name. Начальные пороги600/900; широкий inspector
  только при достаточной высоте и месте для сетки. Пороги не являются persisted mode.
- Детский экран не прокручивает сетку: вся страница целиком, только выбор карточек
  и листание страниц. Фраза и действия закреплены, листание находится в footer.
- При нехватке высоты картинка может стоять рядом с полной подписью. Шрифт не
  уменьшается; touch target не менее48dp, изображение не менее32dp.
- Все страницы выбранной доски проверяются до открытия. «Масштаб не поддерживается»
  показывается только взрослому, без замены прежней доски или фразы. Настройки
  проверяют текущую доску до применения масштаба и до сохранения; остальные наборы
  проходят свою проверку при открытии. Архивы не исправляются ради размещения.
- Сохранённые rows/columns/order/EMPTY/SPACE не меняются от resize, медиа или темы.
- Общая фраза, immutable snapshots, DIRECT/COMPOSE, Stop и UndoClear сохраняются.
- `SaveCard` принимает форму в draft; `Apply` публикует и завершает текущий редактор.
  Кнопка честно обозначает выход. `onHandled` — admission, не durability.
- Side inspector и dialog используют одну форму и тот же input-buffer identity.
- Settings сохраняются явно, preview не пишет. Cancelled/Failed/Unconfirmed различны.
- Access, guards, epoch/revision, lock order, recovery, archive budgets не ослабляются.
- Нативные picker/permissions/owner verification не имитируются. Нет auto-resume,
  повторной речи или записи из recomposition/resize.

## Приёмка и выпуск

Для каждой пары: source/build hash, OS, device, logical/pixel viewport, font scales,
theme, fixture hash, screen state. Проверяются phone/tablet, portrait/landscape,
large text, contrast, IME, keyboard, TalkBack/VoiceOver; automated audit не равен
реальному screen reader. Неизвестное покрытие остаётся OPEN, approval не выводится
из compile/tests/скриншотов. Работа продолжается по поручению пользователя; отдельное
одобрение внешнего вида отмечается только после его ответа.

Новый обязательный atlas — наборы из Electron defaultSets:
«Фраза» Ануровой (2 страницы), «Азбука» (5), «звуки2» (10), крупная клавиатура3×3
(5), «Клавиатура»4×12 (2). Все24 страницы, включая EMPTY/SPACE и DIRECT/LITERAL;
«ХОЧУ» остаётся регрессией, но не заменяет эти исходные наборы. На узком окне
одна страница имеет ровно один viewport-кадр либо подтверждённый взрослый отказ.
Прежние панорамные атласы — история, не приёмка этого контракта. До съёмки ждём
реального появления media, не только стабильности пустого placeholder.

V15 — отдельный admission: release Android пока legacy, development — общий UI.
Production entry переключается только после доказанного migration/upgrade safety,
без копирования debug fixture providers/signing/privacy в release. Нет разрешения
на production-подпись, push/PR/store upload/публикацию или destructive resets.

Уточнение пользователя: для повреждённого «звуки2» разрешена отдельная тестовая
копия; оригинал не меняется, различия и SHA раскрываются. Для старой «Клавиатуры»
поручено копировать обработку Electron, не очищать исходник. Неактивные вложения
EMPTY/SPACE игнорируются при импорте с явным предупреждением до подтверждения.
Доказательства и ограничения: [исходные наборы](smotri-originals-visual-verification.md).

## План поставок

V00 контракт; V01 fixtures/evidence; V02 theme; V03 primitives; V04 scaffold/dialogs;
V05 cards; V06 communication geometry; V07 actions/message; V08 library;
V09 shared card form; V10 adaptive editor; V11 settings; V12 transfer/access;
V13 native polish; V14 полный screenshot/native gate; V15 production admission.
Состояние исполнения — [журнал](electron-ui-progress.md).
