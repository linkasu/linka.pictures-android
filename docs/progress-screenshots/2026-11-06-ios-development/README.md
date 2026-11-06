# iOS development Simulator: реальные кадры

Только `su.linka.pictures.development` на owned iOS17.0
`DD0F9A3F-DC32-4373-BA08-400FECE3A1CC`. Пользователь отдельно разрешил штатную
Simulator-подпись и собственную Keychain-группу после2 actual app-hosted XCTest PASS.
Production bundle/group, profiles, магазины и physical signing не затрагиваются.

Источник — текущий Shared.framework и development App, min17. Существующий sandbox
обновляется через install без reset/uninstall. Новый кадр признаётся UI evidence
только после фактического просмотра; SpringBoard/error не считается библиотекой.
Filled-fixture matching, accessibility/font/keyboard и полный U1.4 approval этим
одним startup-сценарием не закрываются.

`startup-library.png` снят после штатной Simulator-подписи и install без удаления
sandbox; процесс подтверждён живым. Просмотрен primary: настоящая библиотека
«Ваши наборы», пустое состояние/FAB, не SpringBoard и не Keychain error. Пользователь
визуально этот кадр ещё не принимал. Дальше проверяется повторный запуск через
нативный XCUI startup regression, не через fake app/runtime.
