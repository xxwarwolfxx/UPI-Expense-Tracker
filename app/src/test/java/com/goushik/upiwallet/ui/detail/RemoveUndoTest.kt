package com.goushik.upiwallet.ui.detail

import androidx.compose.runtime.saveable.SaverScope
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Host tests for the detail screen's Remove / Undo strip: what it offers, and that its Undo survives a
 *  rotation. */
class RemoveUndoTest {

    private val anyValue = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun rotate(undo: UndoRemove?): UndoRemove? {
        val saved = with(UndoRemoveSaver) { anyValue.save(undo) } ?: return null
        return UndoRemoveSaver.restore(saved)
    }

    @Test fun `a rotation keeps the exact Undo, status and where it was made`() {
        for (status in TxnStatus.entries) {
            for (fromPrompt in listOf(false, true)) {
                val undo = UndoRemove(status, fromPrompt)
                assertEquals(undo, rotate(undo))
            }
        }
        assertNull(rotate(null))
    }

    @Test fun `after a rotation a confirmed payment still offers Undo, not Put back`() {
        val undo = rotate(UndoRemove(TxnStatus.CONFIRMED, fromDuplicatePrompt = false))
        assertEquals(RemoveState.UNDO, removeStateFor(removed = true, undo = undo))
    }

    @Test fun `tapping Undo keeps the Undo until the restore lands - no Put back flash`() {
        val undo = UndoRemove(TxnStatus.CONFIRMED, fromDuplicatePrompt = false)
        // The tap starts the restore; until the row is seen live again it is still removed.
        val whileRestoring = undoAfterRowSeen(undo, removed = true)
        assertEquals(undo, whileRestoring)
        assertEquals(RemoveState.UNDO, removeStateFor(removed = true, undo = whileRestoring))
        // The restore lands: the row is live, and the Undo is gone with it.
        val afterRestore = undoAfterRowSeen(whileRestoring, removed = false)
        assertNull(afterRestore)
        assertEquals(RemoveState.LIVE, removeStateFor(removed = false, undo = afterRestore))
    }

    @Test fun `what the bottom of the receipt offers`() {
        val fromBottom = UndoRemove(TxnStatus.UNCONFIRMED, fromDuplicatePrompt = false)
        val fromPrompt = UndoRemove(TxnStatus.UNCONFIRMED, fromDuplicatePrompt = true)
        assertEquals(RemoveState.LIVE, removeStateFor(removed = false, undo = null))
        assertEquals(RemoveState.LIVE, removeStateFor(removed = false, undo = fromBottom))
        assertEquals(RemoveState.UNDO, removeStateFor(removed = true, undo = fromBottom))
        assertEquals(RemoveState.NONE, removeStateFor(removed = true, undo = fromPrompt))
        assertEquals(RemoveState.PUT_BACK, removeStateFor(removed = true, undo = null))
    }

    @Test fun `a saved value this build doesn't know restores as no Undo`() {
        assertNull(UndoRemoveSaver.restore("SOMETHING_ELSE|true"))
    }
}
