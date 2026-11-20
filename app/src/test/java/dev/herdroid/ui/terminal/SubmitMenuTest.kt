package dev.herdroid.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitMenuTest {
    @Test fun recognizesEitherFocusedOption() {
        assertTrue(hasSubmitMenu(sequenceOf(" ❯ 1. Submit answers", "   2. Cancel")))
        assertTrue(hasSubmitMenu(sequenceOf("│ 1. Submit responses │", "", "│ ❯ 2. Cancel │")))
    }

    @Test fun ignoresInactiveLabelsAndOtherPrompts() {
        assertFalse(hasSubmitMenu(sequenceOf("1. Submit answers", "2. Cancel")))
        assertFalse(hasSubmitMenu(sequenceOf("❯ 1. Yes", "2. Cancel")))
        assertFalse(hasSubmitMenu(sequenceOf("Review your answers", "Submit answers")))
    }
}
