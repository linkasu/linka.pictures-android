package su.linka.pictures.ui.card

import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.CellKind
import su.linka.pictures.domain.EditorForm

internal fun cardContentVisible(form: EditorForm.Card): Boolean = form.kind == CellKind.CONTENT

internal fun cardImageDemand(form: EditorForm.Card): Set<AssetId> =
    if (cardContentVisible(form)) setOfNotNull(form.image) else emptySet()
