package com.loopjet.app

import com.loopjet.app.core.Direction
import com.loopjet.app.core.TransferProgress
import com.loopjet.app.core.TransferState
import com.loopjet.app.platform.SessionUi
import com.loopjet.app.platform.absorbProgress
import com.loopjet.app.platform.percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression test for "one phone shows 16%, the other zero".
 *
 * The two phones fold progress separately, and only the sender used to copy `totalBytes` into the
 * session. The receiver therefore divided by zero-with-a-floor for the whole transfer. These tests
 * pin the arithmetic, so the two screens cannot drift apart again.
 */
class SessionProgressTest {

    private fun frame(
        total: Long,
        done: Long,
        state: TransferState = TransferState.TRANSFERRING,
        filesTotal: Int = 1,
        filesDone: Int = 0,
        speed: Long = 1_000_000
    ) = TransferProgress(
        direction = Direction.RECEIVE,
        sessionId = "s1",
        peerName = "Phone",
        totalBytes = total,
        transferredBytes = done,
        instantBytesPerSecond = speed,
        averageBytesPerSecond = speed,
        filesDone = filesDone,
        filesTotal = filesTotal,
        currentFiles = emptyList(),
        elapsedMs = 1_000,
        etaMs = 5_000,
        state = state
    )

    @Test
    fun theReceivingSideLearnsTheTotalFromTheFirstFrame() {
        val empty = SessionUi()
        assertEquals(0L, empty.totalBytes)
        val receiving = empty.absorbProgress(frame(total = 100_000_000, done = 0))
        assertEquals(100_000_000L, receiving.totalBytes)
        // The bug this replaces: total stayed 0, so the percentage was stuck at 0% for the session.
        assertEquals(0, receiving.percent)
    }

    @Test
    fun bothPhonesReportTheSamePercentageForTheSameBytes() {
        val sender = SessionUi(sending = true, totalBytes = 100_000_000)
            .absorbProgress(frame(total = 100_000_000, done = 16_000_000))
        val receiver = SessionUi(sending = false)
            .absorbProgress(frame(total = 100_000_000, done = 16_000_000))
        assertEquals(sender.totalBytes, receiver.totalBytes)
        assertEquals(sender.doneBytes, receiver.doneBytes)
        assertEquals(16, sender.percent)
        assertEquals(16, receiver.percent)
    }

    @Test
    fun progressNeverGoesBackwards() {
        // Frames come from several streams, so one can arrive late with an older count.
        val early = SessionUi().absorbProgress(frame(total = 10_000, done = 7_000))
        val late = early.absorbProgress(frame(total = 10_000, done = 2_000))
        assertEquals(7_000L, late.doneBytes)
        assertTrue(late.percent >= 70)
    }

    @Test
    fun aDoneSessionIsOneHundredPercentEvenWithUnknownTotal() {
        val finished = SessionUi().absorbProgress(
            frame(total = 0, done = 0, state = TransferState.DONE)
        )
        assertEquals(100, finished.percent)
    }

    @Test
    fun doneBytesCannotOvershootTheTotal() {
        // A manifest that under-counts (a sender that grew a file mid-flight) must not print 130%.
        val ui = SessionUi(totalBytes = 1_000).absorbProgress(frame(total = 1_000, done = 1_300))
        assertEquals(1_000L, ui.doneBytes)
        assertEquals(100, ui.percent)
    }

    @Test
    fun theFileCountUsesTheLargestViewOfTheSession() {
        val ui = SessionUi(filesTotal = 40)
            .absorbProgress(frame(total = 10, done = 1, filesTotal = 12, filesDone = 3))
        assertEquals(40, ui.filesTotal)
        assertEquals(3, ui.filesDone)
    }
}
