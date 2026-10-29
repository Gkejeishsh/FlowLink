package com.loopjet.app.platform

import com.loopjet.app.core.TransferProgress

/**
 * One place that folds a progress frame into the session the UI draws.
 *
 * Why this exists as its own function: the two phones used to fold it separately, and only the
 * sending phone copied `totalBytes`. The receiving phone therefore held a total of 0 for the whole
 * session, and its percentage was whatever `doneBytes / max(total, 1)` happened to give -- 0% for
 * the entire transfer, then a jump to 100%. Users described it exactly that way: "one shows 16% and
 * the other zero".
 *
 * Rules, in order:
 *  * the total only ever grows, and a frame that reports one always wins (the manifest is the source
 *    of truth on both ends, so the two phones end up with the same denominator);
 *  * `doneBytes` never goes backwards, even if an out-of-order frame arrives;
 *  * `filesTotal` takes the largest seen, because the receiver learns about files in batches.
 */
fun SessionUi.absorbProgress(progress: TransferProgress): SessionUi {
    val reportedTotal = progress.totalBytes
    val nextTotal = when {
        reportedTotal > totalBytes -> reportedTotal
        else -> totalBytes
    }
    val nextDone = when {
        progress.transferredBytes > doneBytes -> progress.transferredBytes
        else -> doneBytes
    }
    return copy(
        totalBytes = nextTotal,
        doneBytes = nextDone.coerceAtMost(if (nextTotal > 0) nextTotal else nextDone),
        instantBps = progress.instantBytesPerSecond,
        averageBps = progress.averageBytesPerSecond,
        filesDone = maxOf(progress.filesDone, filesDone),
        filesTotal = maxOf(progress.filesTotal, filesTotal),
        etaMs = progress.etaMs,
        fileProgress = if (progress.fileProgress.isNotEmpty()) progress.fileProgress else fileProgress,
        pathLabel = progress.pathLabel.ifBlank { pathLabel },
        state = progress.state,
        speedHistory = (speedHistory + progress.instantBytesPerSecond).takeLast(120)
    )
}

/** Percentage 0..100 for a session, defined once so both phones cannot disagree. */
val SessionUi.percent: Int
    get() = when {
        state == com.loopjet.app.core.TransferState.DONE -> 100
        totalBytes <= 0 -> 0
        else -> ((doneBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }
