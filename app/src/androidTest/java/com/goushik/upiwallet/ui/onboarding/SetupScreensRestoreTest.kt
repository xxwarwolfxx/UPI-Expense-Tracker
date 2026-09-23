package com.goushik.upiwallet.ui.onboarding

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goushik.upiwallet.ui.theme.UPIWalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup's typed fields survive the Activity being recreated (a rotation, a theme switch, or the app being
 * killed while the user looks up an ID in GPay) before Continue hands them up to the flow. Invented values.
 */
@RunWith(AndroidJUnit4::class)
class SetupScreensRestoreTest {

    @get:Rule val rule = createComposeRule()

    @Test fun identityKeepsTheNameTheAddedIdsAndATypedId() {
        val restore = StateRestorationTester(rule)
        restore.setContent {
            UPIWalletTheme { IdentityScreen(initialName = "", initialVpas = emptyList(), onContinue = { _, _ -> }) }
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Juniper Quill")
        fields[1].performTextInput("juniper@okaxis")
        rule.onNodeWithText("Add").performClick()
        fields[1].performTextInput("quill@okhdfcbank") // typed, not added yet

        restore.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("Juniper Quill").assertExists()
        rule.onNodeWithText("juniper@okaxis").assertExists()
        rule.onNodeWithText("quill@okhdfcbank").assertExists()
    }

    @Test fun identityWithNothingAddedYetComesBackEmptyAndContinues() {
        var continued: List<String>? = null
        val restore = StateRestorationTester(rule)
        restore.setContent {
            UPIWalletTheme {
                IdentityScreen(initialName = "", initialVpas = emptyList(), onContinue = { _, v -> continued = v })
            }
        }
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Juniper Quill")

        restore.emulateSavedInstanceStateRestore()

        rule.onAllNodesWithText("Continue")[0].performClick()
        rule.runOnIdle { assertEquals(emptyList<String>(), continued) }
    }

    @Test fun balanceKeepsBothTypedAmounts() {
        val restore = StateRestorationTester(rule)
        restore.setContent {
            UPIWalletTheme { BalanceScreen(initialHdfc = "", initialSbi = "", onContinue = { _, _, _, _ -> }) }
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("1234")
        fields[1].performTextInput("567")

        restore.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("1234").assertExists()
        rule.onNodeWithText("567").assertExists()
    }
}
