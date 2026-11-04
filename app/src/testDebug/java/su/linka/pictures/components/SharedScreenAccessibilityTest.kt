package su.linka.pictures.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.BoardDocument
import su.linka.pictures.Card
import su.linka.pictures.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w320dp-h900dp")
class SharedScreenAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private val document = BoardDocument(rows = 1, columns = 4, cards = listOf(
        Card(0, title = "Water", imagePath = "water.png"),
        Card(1, cardType = 1),
        Card(2, cardType = 2),
    ))

    // Deliberately ignores the slot modifier: the screen owns decorative-image semantics.
    private val image: @Composable (String?, String?, Modifier) -> Unit = { _, _, _ ->
        Layout(content = {}, modifier = Modifier.semantics { contentDescription = "Duplicate image name" }) { _, constraints ->
            layout(constraints.minWidth, constraints.minHeight) {}
        }
    }

    @Test fun boardCardsHaveNamesAndPlaceholdersHaveNoActionOrFocus() {
        val actions = mutableListOf<BoardAction>()
        compose.setContent {
            PicturesTheme {
                BoardScreen(document, 0, emptyList(), false, false, false, actions::add, image)
            }
        }
        compose.onNodeWithText("Water").assertHasClickAction().assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Space").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Duplicate image name").assertDoesNotExist()
        // Two toolbar actions and two real cards; empty/absent slots add neither action nor focus.
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        compose.onAllNodes(isFocusable()).assertCountEquals(4)
        compose.runOnIdle {
            assertEquals(listOf(BoardAction.SelectCard(0), BoardAction.SelectCard(1)), actions)
        }
    }

    @Test fun busyBoardExposesDisabledCardsAndPhraseActions() {
        compose.setContent {
            PicturesTheme {
                BoardScreen(document, 0, emptyList(), true, false, true, {}, image)
            }
        }
        compose.onNodeWithText("Water").assertIsNotEnabled()
        compose.onNodeWithText("Space").assertIsNotEnabled()
        compose.onNodeWithText("Speak").assertIsNotEnabled()
        compose.onNodeWithText("Clear phrase").assertIsNotEnabled()
    }

    @Test fun communicationPlaceholdersNeverEnterActionOrFocusNavigation() {
        compose.setContent {
            PicturesTheme {
                BoardScreen(document.copy(cards = listOf(Card(0, cardType = 2), Card(1, cardType = 3))),
                    0, emptyList(), false, false, false, {}, image)
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onAllNodes(isFocusable()).assertCountEquals(2)
    }

    @Test fun untitledCardStillHasAnActionName() {
        compose.setContent {
            PicturesTheme {
                BoardScreen(document.copy(cards = listOf(Card(0, title = "   "))), 0,
                    emptyList(), false, false, false, {}, image)
            }
        }
        compose.onNodeWithText("Untitled card").assertHasClickAction().assertIsEnabled()
    }

    @Test fun phraseImagesAreDecorativeAndPhraseCardsAreNotDisabledButtons() {
        compose.setContent {
            PicturesTheme {
                BoardScreen(document.copy(cards = emptyList()), 0, listOf(document.cards.first()),
                    true, false, false, {}, image)
            }
        }
        compose.onNodeWithText("Water").assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        compose.onNodeWithContentDescription("Duplicate image name").assertDoesNotExist()
    }

    @Test fun keyboardPhraseUsesVerbatimTitlesAndExplicitSpaces() {
        val phrase = listOf(Card(0, title = "I"), Card(1, title = "want"),
            Card(2, cardType = 1), Card(3, title = "water"))
        compose.setContent {
            PicturesTheme {
                BoardScreen(document.copy(withoutSpace = true), 0, phrase, true, false, false, {}, image)
            }
        }
        compose.onNodeWithText("Iwant water").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Speak").assertIsEnabled()
    }

    @Test fun editorBlankSlotsAreNamedAndToggleIsOneLabeledAction() {
        val actions = mutableListOf<EditorAction>()
        compose.setContent {
            PicturesTheme {
                EditorScreen(document.copy(columns = 1, cards = emptyList()), 0, false, null, actions::add, image)
            }
        }
        compose.onNodeWithText("Add or edit card").performScrollTo().assertHasClickAction().performClick()
        val toggle = compose.onNodeWithText("Do not insert spaces automatically")
        toggle.performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(listOf(EditorAction.EditCard(0), EditorAction.WithoutSpace(true)), actions)
        }
    }

    @Test fun busyEditorToggleKeepsItsLabelAndDisabledState() {
        compose.setContent {
            PicturesTheme { EditorScreen(document, 0, true, null, {}, image) }
        }
        compose.onNodeWithText("Do not insert spaces automatically").performScrollTo()
            .assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun libraryCoverDoesNotDuplicateSetNameAndOptionsNameTheirSet() {
        val set = SetSummary("Drinks.linka")
        val actions = mutableListOf<LibraryAction>()
        compose.setContent {
            PicturesTheme {
                LibraryScreen(listOf(set), false, null, actions::add,
                    image = { _, modifier -> image(null, null, modifier) })
            }
        }
        compose.onNodeWithText("Drinks").assertHasClickAction().performClick()
        compose.onNodeWithText("Options for Drinks").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Duplicate image name").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(LibraryAction.Open(set), LibraryAction.Options(set)), actions)
        }
    }

    @Test fun busyLibraryKeepsNamesAndExposesDisabledActions() {
        compose.setContent {
            PicturesTheme {
                LibraryScreen(listOf(SetSummary("Drinks.linka")), true, null, {},
                    image = { _, modifier -> image(null, null, modifier) })
            }
        }
        compose.onNodeWithText("Drinks").assertIsNotEnabled()
        compose.onNodeWithText("Options for Drinks").assertIsNotEnabled()
        compose.onNodeWithText("Create set").assertIsNotEnabled()
    }

    @Test fun settingsRecoveryExposesOneDisabledSwitchPerSetting() {
        compose.setContent {
            PicturesTheme { SettingsScreen(true, false, true, {}) }
        }
        compose.onNodeWithText("Parental control").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Send usage analytics").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun compactLargeFontBoardRetainsLogicalPageAndReachableCards() = checkLargeFontBoard()

    @Test @Config(qualifiers = "en-w600dp-h900dp")
    fun tabletLargeFontBoardRetainsLogicalPageAndReachableCards() = checkLargeFontBoard()

    private fun checkLargeFontBoard() {
        val cards = (0..7).map { Card(it, title = "Card $it") }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PicturesTheme {
                    BoardScreen(BoardDocument(rows = 2, columns = 4, cards = cards), 0,
                        emptyList(), false, true, false, {}, image)
                }
            }
        }
        cards.forEach { card ->
            compose.onNodeWithText(card.title!!).performScrollTo().assertIsDisplayed()
                .assertWidthIsAtLeast(96.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Next page").assertDoesNotExist()
    }
}
