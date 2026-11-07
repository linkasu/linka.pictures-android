# Изолированный native restore/recovery: контракт проверки

Пользователь разрешил этот сценарий и последующие операции со своими расходными
симуляторами без повторных вопросов. Только новый
`linka_recovery_probe_ios17_67846f` / `EA356A64-6182-404B-B78A-54E9791129DB`.
Существующие устройства и исходный backup не являются целью замены.

| Шаг | Текущая библиотека | Recovery |
|---|---|---|
| Начало | Пустая | Нет |
| UI sentinel | «Новая доска»,3×3, одна SPACE,0 assets | Нет |
| Files → restore backup | «Импортированная доска»,3×3,9 CONTENT/18 assets | Sentinel |
| Явный возврат | Sentinel | Импортированная доска |
| Явное удаление тестовой точки | Sentinel | Нет |

Исходный проверенный backup1.0 остаётся в isolated host Documents, SHA
`1c198000ed5be181f8d8102b8f800ec69cb94adc7af3d40c279f383f8ad0baed`.
Данные продукта не seed-ятся через SQL/private APIs: sentinel создаётся обычным
UI, входной backup выбирается настоящим Files. На всех шагах сообщение пустое;
схема backup не переносит пароль, локальные голоса или сообщение. Credential store
не меняется сценарием восстановления.

Перед любым действием тест проверяет ожидаемый/фактический Simulator UDID и
пустую библиотеку. Каждая замена/возврат/удаление имеет собственные foreground
grant и отдельное подтверждение. Commit epoch переносится только после COMPLETED;
новый runtime перечитывает библиотеку. При FAILED/UNCONFIRMED/неожиданной библиотеке
тест останавливается, действие не повторяет и не маскирует reset-ом.

Это native-проверка существующего контракта, не изменение DB/schema/writer locks
или production startup. Старый и новый binary/schema не смешиваются; rollback
проверяется публичным ReturnToRecovery. UI counts, доступность9 карточек, возврат
sentinel и отсутствие recovery после удаления должны быть подтверждены отдельно.
## Результат: native PASS, 7 ноября

**1/1 PASS,226.584s**. `recovery-native-evidence.json` содержит SHA десяти
неизменённых PNG, guard и xcresult. Primary просмотрел восстановленную доску,
исходную recovery-точку, отсутствие точки после удаления и финальную cold library.
Последовательность таблицы подтверждена; в конце осталась только «Новая доска».
Исходный и скопированный backup сохранили SHA, продуктовая БД не seed-илась.

История setup failures сохранена: исходный скрипт ожидал FormatVersion2 вместо1;
затем перенос xctestrun из Products нарушил `__TESTROOT__` (code70 Missing test
product). Оба отказа были до native-теста, не ошибками restore. Успешный guarded
xctestrun оставлен рядом с test products; native guard сверил Simulator UDID.

Это положительная iOS17 synthetic-проверка, не Android restore, настоящий upgrade,
старый binary/new-schema rollback, physical/AAC или разрешение публикации.
