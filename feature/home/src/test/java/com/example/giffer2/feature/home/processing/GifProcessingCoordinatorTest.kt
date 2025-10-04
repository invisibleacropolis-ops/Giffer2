package com.example.giffer2.feature.home.processing

import androidx.work.WorkInfo
import com.example.gifvision.GifWorkProgress
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class GifProcessingCoordinatorTest {

    @Test
    fun `progress prefers serialized payload when present`() {
        val workId = UUID.randomUUID()
        val expected = GifWorkProgress(workId, percent = 42, stage = GifWorkProgress.Stage.RENDERING)
        val workInfo = WorkInfo.Builder(workId, WorkInfo.State.RUNNING)
            .setProgress(expected.toData())
            .build()

        val mapped = workInfo.toGifWorkProgress(
            previous = GifWorkProgress(workId, percent = 0, stage = GifWorkProgress.Stage.QUEUED)
        )

        assertEquals(expected.percent, mapped.percent)
        assertEquals(expected.stage, mapped.stage)
    }

    @Test
    fun `progress falls back to state heuristics`() {
        val workId = UUID.randomUUID()
        val workInfo = WorkInfo.Builder(workId, WorkInfo.State.RUNNING).build()
        val previous = GifWorkProgress(workId, percent = 0, stage = GifWorkProgress.Stage.QUEUED)

        val mapped = workInfo.toGifWorkProgress(previous)

        assertEquals(10, mapped.percent)
        assertEquals(GifWorkProgress.Stage.PREPARING, mapped.stage)
    }

    @Test
    fun `successful work reports completion`() {
        val workId = UUID.randomUUID()
        val workInfo = WorkInfo.Builder(workId, WorkInfo.State.SUCCEEDED).build()
        val previous = GifWorkProgress(workId, percent = 75, stage = GifWorkProgress.Stage.RENDERING)

        val mapped = workInfo.toGifWorkProgress(previous)

        assertEquals(100, mapped.percent)
        assertEquals(GifWorkProgress.Stage.COMPLETED, mapped.stage)
    }
}
