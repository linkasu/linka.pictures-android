# Visible image demand: presentation integration

## Backward-compatible seams

The existing parameters keep their order and defaults. New trailing parameters:

- `CommunicationScreen(..., modifier: Modifier = Modifier, showPageButtons: Boolean = true, onImagesNeeded: ((Set<AssetId>) -> Unit)? = null)`
- `EditorScreen(..., modifier: Modifier = Modifier, onImagesNeeded: ((Set<AssetId>) -> Unit)? = null)`
- `CardEditorScreen(..., modifier: Modifier = Modifier, onImagesNeeded: ((Set<AssetId>) -> Unit)? = null)`

`EditorScreen` forwards its callback to the card form itself; roots using that entry point must not register a second form demand. Null preserves manually supplied borrowed image maps. Image-map keys are never used as a readiness signal.

## Composition ownership

Each screen owns a `VisibleImageRegistry`. It reports the union of composed content-row images and composed message-part snapshot images, not the whole board, document or message. Lazy composition can include the framework's prefetched rows; horizontal rows deliberately request all their composed columns.

- Communication: `LazyColumn` row `(pageId, row)` and `LazyRow` part ID.
- Editor: selected-page lazy rows only while the grid is actually rendered; no grid/settings-form demand.
- Card form: only `form.image`, including a pending preview without decoded pixels.

Every effect incarnation has a distinct registration token, so disposal of an old page/part cannot erase a replacement. Communication changes registry on message epoch; editor/form on `(epoch, ownerId, draftId)`. Disposal clears the entire screen demand and closes the registry against late effects. Callback replacement uses the latest callback for subsequent updates and disposal; replacing a callback is not a separate loader owner.

This registry does not load media, hold leases, enumerate the library, access files or impose decode/cache policy. Root owns those operations and the 32 MiB bitmap budget.

## Paging and accessibility

`showPageButtons` affects only previous/next button presentation. The page indicator exposes real Compose `customActions` for available previous/next transitions. Disabled commands and boundaries remove unavailable actions. PageUp/PageDown work from focused grid/actions, activate once on physical key release, ignore repeat key-down events and modifier chords, and do not intercept the literal text field. Existing primitive focus rings are unchanged. Paging dispatches only `ChangePage`; reducers and the active COMPOSE speech plan are unchanged.

## Parent integration still required

This branch has no `LinkaApp`/`CommonRoot` implementation or screen callers outside `EditorScreen -> CardEditorScreen`. The parent P13 root must bind the real `LinkaApp` calls to:

1. `CommunicationScreen.onImagesNeeded`: screen-owned communication demand in its thumbnail loader; `showPageButtons` from `PortableSettings.boards[currentBoard].showPageButtons` (existing default when no stored entry).
2. `EditorScreen.onImagesNeeded`: screen-owned editor demand in the same loader, supplying resulting borrowed pixels via `images`.
3. A direct `CardEditorScreen` caller, if the parent adds one, needs the same editor-owned binding, not a second concurrent registration for the same form.

Screen callback `emptySet()` releases that screen's demand, not every other consumer's leases. Root bindings must keep screen owners distinct and be generation-safe across restore/owner changes. `ThumbnailLoader.kt`, native image sources, source export and root authoring pixels are outside this change.

Pure common tests cover registration union/deduplication, stale-effect removal, current callback/disposal, closure, null compatibility, selected row rather than all-page demand, keyboard repeat, typed focus, modifiers, disabled navigation and boundaries. Device screenshots and real VoiceOver/TalkBack validation remain a parent acceptance gate; these tests do not claim that gate is complete.

## Verified in this worktree

- `:shared:jvmTest --tests 'su.linka.pictures.ui.*' --offline`: **21 passed**, including 9 new tests, P10/P11 presentation tests and theme tests.
- Final isolated `:shared:jvmTest --offline`: **343 passed**, no failures/errors.
- `git diff --check`: passed.
- Both Native compile tasks were requested, but stopped before Kotlin Native compilation: the worktree had no verified decoder outputs, and `prepareIosAudioDecoder` began fresh FFmpeg configuration. No Native compile success is claimed. The parent can rerun both tasks against its verified existing decoder cache after integration; no device, permission, signing or native-root operations were performed.
