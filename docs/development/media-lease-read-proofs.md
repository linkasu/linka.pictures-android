# Чтение media через составной lease

6 ноября 2026: нажатие «Произнести» на сохранённой iOS-доске давало
`STALE_REQUEST` без замены библиотеки. Вызов шёл через
`CommunicationSession.play` → `MessageLease.retain` → `AudioCoordinator.execute` →
`StoredMedia.file` → `StoragePins.allows`. Проверка принимала только прямой
`StoragePins.Lease`; действительный leaf внутри `MessageLease` отвергался.
`AuthoringLease` имел ту же несовместимость для preview.

Исправление: внутренний `DelegatingMediaLease.readProof` разрешает составную
ссылку до заимствованного leaf. Storage по-прежнему проверяет настоящий тип,
точный registry owner, незакрытое состояние и принадлежность asset. Одного
совпадения `assets` или существования pin недостаточно. Чужие, закрытые и
поддельные leases не становятся авторизованными.

Публичный `MediaLease`/`MediaStore` interface не изменён. Нет повторного acquire,
окна между release/acquire, новых pins или операций IO при разрешении proof.
Составные ссылки разрешаются **до** pins lock; callbacks под этим lock не вызываются.
COMPOSE, DIRECT и editor preview сохраняют прежние ownership/Stop/queue rules.
Порядок writer mutex → pins lock и счётчики register/close не меняются.

Схема БД, persisted JSON и файлы media не меняются; миграция не нужна. Пересборка
обеих платформ обязательна. Откат возвращает прежнюю ошибку составного чтения,
но не требует преобразования данных. Production startup не переключался.

Regression до исправления: actual SQLite/media integration и составное чтение
дали **2/2 failures**, в том числе `expected COMPLETED, actual FAILED/STALE_REQUEST`.
После исправления первоначальный focused gate: **212 tests, 0 failures/errors/skips**.
Дополнительно проверяется отзыв закрытого wrapper даже при ошибке его cleanup,
когда leaf ещё жив. Полные suites и native UI фиксируются отдельно, не выводятся
из результата fake native audio endpoint в integration-тесте.
