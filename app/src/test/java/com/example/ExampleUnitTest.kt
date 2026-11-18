package com.example

import com.example.data.model.AspectRatioOption
import com.example.data.model.PipelineStatus
import com.example.data.model.ProductionMode
import com.example.data.model.ThumbnailStyle
import com.example.data.model.VideoFormat
import com.example.data.model.VoiceGender
import com.example.data.model.VoiceStyle
import com.example.pipeline.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun sarvcutModelEnums_fallbackSafelyOnUnknownValues() {
        assertEquals(VideoFormat.DOCUMENTARY, VideoFormat.fromRaw("UNKNOWN_FORMAT"))
        assertEquals(AspectRatioOption.YOUTUBE_16_9, AspectRatioOption.fromWire("4:3"))
        assertEquals(AspectRatioOption.SHORTS_9_16, AspectRatioOption.fromWire("9:16"))
        assertEquals(VoiceGender.FEMALE, VoiceGender.fromRaw(null))
        assertEquals(VoiceStyle.CINEMATIC, VoiceStyle.fromRaw("UNKNOWN"))
        assertEquals(ProductionMode.STANDARD_HYBRID, ProductionMode.fromRaw("NONE"))
        assertEquals(PipelineStatus.QUEUED, PipelineStatus.fromRaw("BAD_STATUS"))
        assertEquals(ThumbnailStyle.BOLD_CINEMA, ThumbnailStyle.fromRaw("INVALID"))
    }

    @Test
    fun aspectRatioDimensions_match1080pTargets() {
        assertEquals(1920, AspectRatioOption.YOUTUBE_16_9.widthPx)
        assertEquals(1080, AspectRatioOption.YOUTUBE_16_9.heightPx)
        assertEquals(1080, AspectRatioOption.SHORTS_9_16.widthPx)
        assertEquals(1920, AspectRatioOption.SHORTS_9_16.heightPx)
    }

    @Test
    fun providerRegistry_honestlyReportsVeoClientUnavailability() {
        val statuses = ProviderRegistry.listStatuses(
            searchGroundingEnabled = true,
            nanoBananaEnabled = true,
            geminiTtsEnabled = true,
            veoServerAdapterEnabled = false
        )
        val veo = statuses.first { it.modelOrEngine.contains("Veo") }
        assertFalse(veo.availableOnClient)
        assertTrue(veo.statusNote.contains("Unavailable", ignoreCase = true))
    }
}
