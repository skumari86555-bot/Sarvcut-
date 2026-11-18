package com.example.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.model.AspectRatioOption
import com.example.data.model.ProviderAdapterStatus
import com.example.data.model.ResearchSource
import com.example.data.model.VideoFormat
import com.example.data.model.VoiceGender
import com.example.data.model.VoiceStyle
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AiAdapters"

data class GroundedResearchOutput(
    val summary: String,
    val verificationNotes: String,
    val conflictingInfo: String?,
    val sources: List<ResearchSource>,
    val groundingQueries: List<String>,
    val estimatedTokens: Long
)

data class GeneratedSceneDraft(
    val index: Int,
    val heading: String,
    val narration: String,
    val translatedNarration: String?,
    val visualPrompt: String,
    val subtitleText: String,
    val durationSeconds: Int
)

data class GeneratedScriptOutput(
    val title: String,
    val intro: String,
    val outro: String,
    val scenes: List<GeneratedSceneDraft>,
    val estimatedTokens: Long
)

data class VoiceSynthesisResult(
    val wavFile: File,
    val durationMillis: Long,
    val providerUsed: String
)

interface ResearchProvider {
    suspend fun researchAndVerify(topic: String, language: String, useSearchGrounding: Boolean): GroundedResearchOutput
}

interface ScriptProvider {
    suspend fun writeScriptAndScenes(
        topic: String,
        research: GroundedResearchOutput,
        format: VideoFormat,
        language: String,
        translatedLanguage: String?,
        targetDurationSeconds: Int,
        aspectRatio: AspectRatioOption
    ): GeneratedScriptOutput
}

interface VisualProvider {
    suspend fun generateSceneBitmap(
        prompt: String,
        aspectRatio: AspectRatioOption,
        outFile: File
    ): File
}

interface VoiceProvider {
    suspend fun synthesizeVoiceToWav(
        text: String,
        language: String,
        gender: VoiceGender,
        style: VoiceStyle,
        outFile: File,
        preferGeminiTts: Boolean
    ): VoiceSynthesisResult
}

object ProviderRegistry {
    fun listStatuses(
        searchGroundingEnabled: Boolean,
        nanoBananaEnabled: Boolean,
        geminiTtsEnabled: Boolean,
        veoServerAdapterEnabled: Boolean
    ): List<ProviderAdapterStatus> = listOf(
        ProviderAdapterStatus(
            name = "Research & Verification",
            modelOrEngine = "gemini-3.8-flash + Tool.googleSearch()",
            availableOnClient = searchGroundingEnabled,
            statusNote = if (searchGroundingEnabled) "Active via Firebase AI Logic + App Check" else "Disabled by admin config"
        ),
        ProviderAdapterStatus(
            name = "Script & Scene Planner",
            modelOrEngine = "gemini-3.8-flash (Structured Schema)",
            availableOnClient = true,
            statusNote = "Active JSON schema pipeline"
        ),
        ProviderAdapterStatus(
            name = "AI Image & Thumbnail",
            modelOrEngine = "gemini-nano-banana-2.1",
            availableOnClient = nanoBananaEnabled,
            statusNote = if (nanoBananaEnabled) "Active (requires Firebase Blaze plan for quota)" else "Disabled by admin config"
        ),
        ProviderAdapterStatus(
            name = "AI Voice Narration",
            modelOrEngine = "gemini-3.1-flash-tts-preview + Android TTS Fallback",
            availableOnClient = true,
            statusNote = if (geminiTtsEnabled) "Gemini TTS primary with on-device WAV synthesis fallback" else "Android on-device TTS active"
        ),
        ProviderAdapterStatus(
            name = "AI Video Clip Generator",
            modelOrEngine = "Google Veo API",
            availableOnClient = false,
            statusNote = if (veoServerAdapterEnabled) {
                "Server-side Cloud Run / Vertex AI adapter required (not in Android client SDK)"
            } else {
                "Unavailable on Android client SDK — using Nano Banana motion scenes + H.264 render"
            }
        ),
        ProviderAdapterStatus(
            name = "Automatic Video Editor",
            modelOrEngine = "Android MediaCodec (H.264 + AAC) + MediaMuxer",
            availableOnClient = true,
            statusNote = "On-device hardware-accelerated MP4 compositor active"
        )
    )
}

class GeminiResearchAdapter : ResearchProvider {
    private val groundedSearchModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch())
        )
    }

    private val plainResearchModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    private val verificationSchema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(description = "Comprehensive, factual research summary in the target language"),
            "verificationNotes" to Schema.string(description = "Cross-source verification assessment explaining which facts were confirmed across independent sources"),
            "conflictingInfo" to Schema.string(description = "Any uncertain, evolving, or conflicting claims found across sources, or empty string if none"),
            "sources" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "title" to Schema.string(description = "Source page or publication title"),
                        "url" to Schema.string(description = "Exact source URL returned by grounding; never invent URLs"),
                        "publishedDate" to Schema.string(description = "Publication date if found in search results, otherwise 'Undated'"),
                        "credibilityNote" to Schema.string(description = "Short note on source authority and what claim it supports")
                    )
                ),
                description = "Verified sources strictly from the grounded search metadata"
            )
        ),
        optionalProperties = listOf("conflictingInfo")
    )

    private val verificationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = verificationSchema
            }
        )
    }

    override suspend fun researchAndVerify(
        topic: String,
        language: String,
        useSearchGrounding: Boolean
    ): GroundedResearchOutput {
        val searchPrompt = """
            Research the following video topic thoroughly using Google Search:
            Topic: "$topic"
            Target output language: $language
            
            Instructions:
            1. Gather recent, authoritative facts, statistics, timelines, and context from multiple independent sources.
            2. Identify any conflicting data, uncertainties, or recent developments.
            3. Never invent URLs, publication dates, or citations.
            4. Write a detailed factual brief citing the findings clearly.
        """.trimIndent()

        val groundedResponse = withTimeout(60_000L) {
            if (useSearchGrounding) {
                try {
                    groundedSearchModel.generateContent(searchPrompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Google Search grounding call failed, retrying without grounding tool: ${e.message}")
                    plainResearchModel.generateContent(searchPrompt)
                }
            } else {
                plainResearchModel.generateContent(searchPrompt)
            }
        }

        val rawFindings = groundedResponse.text?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Research model returned empty findings.")

        val candidate = groundedResponse.candidates.firstOrNull()
        val groundingMeta = candidate?.groundingMetadata
        val searchQueries = groundingMeta?.webSearchQueries.orEmpty()
        val realChunks = groundingMeta?.groundingChunks.orEmpty().mapNotNull { chunk ->
            val web = chunk.web
            val uri = web?.uri.orEmpty()
            val title = web?.title.orEmpty()
            val domain = web?.domain.orEmpty()
            if (uri.isNotBlank()) {
                ResearchSource(
                    title = title.ifBlank { domain.ifBlank { "Web Source" } },
                    url = uri,
                    publishedDate = "Verified via Google Search",
                    credibilityNote = "Grounded domain: ${domain.ifBlank { "web" }}"
                )
            } else null
        }.distinctBy { it.url }.take(12)

        val groundingContextText = if (realChunks.isNotEmpty()) {
            realChunks.joinToString("\n") { "- ${it.title} | ${it.url} | ${it.credibilityNote}" }
        } else {
            "No external web URLs were returned by grounding metadata. Do NOT fabricate URLs; return an empty sources list if none were provided."
        }

        val verifyPrompt = """
            You are a strict fact-verification editor for SARVCUT.
            Verify the research brief below in $language for the topic: "$topic".
            
            Grounded Findings:
            $rawFindings
            
            Real Grounding URLs (ONLY cite URLs from this list; NEVER invent a URL):
            $groundingContextText
            
            Produce a structured verification report checking consistency across independent points, flagging uncertain or conflicting claims, and summarizing verified facts.
        """.trimIndent()

        val verifyResponse = withTimeout(60_000L) {
            verificationModel.generateContent(verifyPrompt)
        }
        val jsonText = verifyResponse.text?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Verification model returned empty JSON.")

        val json = JSONObject(jsonText)
        val summary = json.optString("summary", rawFindings)
        val verificationNotes = json.optString("verificationNotes", "Cross-checked grounded findings.")
        val conflictingInfo = json.optString("conflictingInfo", "").takeIf { it.isNotBlank() }

        val parsedSources = mutableListOf<ResearchSource>()
        val srcArr = json.optJSONArray("sources")
        val allowedUrls = realChunks.map { it.url }.toSet()
        if (srcArr != null) {
            for (i in 0 until srcArr.length()) {
                val obj = srcArr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").trim()
                if (url.isNotBlank() && (allowedUrls.isEmpty() || url in allowedUrls)) {
                    parsedSources.add(
                        ResearchSource(
                            title = obj.optString("title", "Source").take(200),
                            url = url.take(500),
                            publishedDate = obj.optString("publishedDate", "Undated").take(60),
                            credibilityNote = obj.optString("credibilityNote", "").take(300)
                        )
                    )
                }
            }
        }
        val finalSources = if (realChunks.isNotEmpty()) {
            (parsedSources + realChunks).distinctBy { it.url }.take(12)
        } else {
            emptyList()
        }

        val tokensEstimate = ((searchPrompt.length + rawFindings.length + verifyPrompt.length + jsonText.length) / 4).toLong()
        return GroundedResearchOutput(
            summary = summary,
            verificationNotes = verificationNotes,
            conflictingInfo = conflictingInfo,
            sources = finalSources,
            groundingQueries = searchQueries,
            estimatedTokens = tokensEstimate
        )
    }
}

class GeminiScriptAdapter : ScriptProvider {
    private val scriptSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Compelling video title under 100 characters"),
            "intro" to Schema.string(description = "Engaging hook introduction"),
            "outro" to Schema.string(description = "Memorable closing call-to-action or takeaway"),
            "scenes" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "heading" to Schema.string(description = "Short 2-5 word scene chapter heading"),
                        "narration" to Schema.string(description = "Spoken voiceover narration for this scene in the target language"),
                        "translatedNarration" to Schema.string(description = "Accurate translation into the requested secondary language, or empty string if not requested"),
                        "visualPrompt" to Schema.string(description = "Rich cinematic visual prompt for AI image/frame generation matching the aspect ratio, no text in image"),
                        "subtitleText" to Schema.string(description = "Clean subtitle captions matching the spoken narration"),
                        "durationSeconds" to Schema.integer(description = "Recommended scene duration in seconds")
                    )
                ),
                description = "Ordered list of video scenes"
            )
        )
    )

    private val scriptModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = scriptSchema
            }
        )
    }

    override suspend fun writeScriptAndScenes(
        topic: String,
        research: GroundedResearchOutput,
        format: VideoFormat,
        language: String,
        translatedLanguage: String?,
        targetDurationSeconds: Int,
        aspectRatio: AspectRatioOption
    ): GeneratedScriptOutput {
        val desiredScenes = when {
            targetDurationSeconds <= 45 -> 4
            targetDurationSeconds <= 90 -> 5
            targetDurationSeconds <= 180 -> 7
            targetDurationSeconds <= 420 -> 10
            else -> 12
        }
        val translationInstruction = if (!translatedLanguage.isNullOrBlank() && translatedLanguage != language) {
            "Also populate translatedNarration with a natural, accurate translation in $translatedLanguage."
        } else {
            "Set translatedNarration to empty string."
        }

        val prompt = """
            You are an award-winning video director and scriptwriter for SARVCUT.
            Create a production-ready ${format.label} script for a ${aspectRatio.label} video (~$targetDurationSeconds seconds total).
            
            Topic: "$topic"
            Primary Language: $language
            $translationInstruction
            Number of scenes: $desiredScenes
            
            Verified Research Brief:
            ${research.summary.take(6000)}
            
            Verification Notes:
            ${research.verificationNotes.take(1500)}
            
            Requirements:
            - Total scene durations should sum close to $targetDurationSeconds seconds (each scene 6 to 30 seconds).
            - Keep narration natural for spoken voiceover, grounded strictly in the verified research above.
            - Visual prompts must describe concrete, photorealistic or stylized cinematic imagery with consistent palette and zero overlaid text.
            - Subtitles should match the spoken narration clearly.
        """.trimIndent()

        val response = withTimeout(60_000L) {
            scriptModel.generateContent(prompt)
        }
        val jsonText = response.text?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Script writer returned empty JSON.")

        val root = JSONObject(jsonText)
        val title = root.optString("title", topic.take(80)).ifBlank { topic.take(80) }
        val intro = root.optString("intro", "Welcome to this deep dive into $topic.")
        val outro = root.optString("outro", "Thanks for watching.")
        val scenesArr = root.optJSONArray("scenes")
            ?: throw IllegalStateException("Script JSON missing scenes array.")

        val scenes = mutableListOf<GeneratedSceneDraft>()
        for (i in 0 until scenesArr.length()) {
            val s = scenesArr.optJSONObject(i) ?: continue
            val narration = s.optString("narration", "").trim().ifBlank { "Exploring $topic." }
            val translated = s.optString("translatedNarration", "").trim().takeIf { it.isNotBlank() }
            val subtitle = s.optString("subtitleText", narration).trim().ifBlank { narration }
            val dur = s.optInt("durationSeconds", (targetDurationSeconds / desiredScenes.coerceAtLeast(1)).coerceIn(6, 30))
                .coerceIn(4, 120)
            scenes.add(
                GeneratedSceneDraft(
                    index = i,
                    heading = s.optString("heading", "Scene ${i + 1}").take(150),
                    narration = narration,
                    translatedNarration = translated,
                    visualPrompt = s.optString("visualPrompt", "Cinematic scene illustrating $topic").take(2000),
                    subtitleText = subtitle,
                    durationSeconds = dur
                )
            )
        }
        if (scenes.isEmpty()) throw IllegalStateException("No scenes were generated.")

        val tokensEstimate = ((prompt.length + jsonText.length) / 4).toLong()
        return GeneratedScriptOutput(
            title = title,
            intro = intro,
            outro = outro,
            scenes = scenes,
            estimatedTokens = tokensEstimate
        )
    }
}

class GeminiImageAdapter : VisualProvider {
    override suspend fun generateSceneBitmap(
        prompt: String,
        aspectRatio: AspectRatioOption,
        outFile: File
    ): File {
        val ratio = when (aspectRatio) {
            AspectRatioOption.YOUTUBE_16_9 -> AspectRatio.LANDSCAPE_16x9
            AspectRatioOption.SHORTS_9_16 -> AspectRatio.PORTRAIT_9x16
        }
        val config = generationConfig {
            responseModalities = listOf(ResponseModality.IMAGE)
            imageConfig = ImageConfig.builder()
                .setAspectRatio(ratio)
                .setImageSize(ImageSize.SIZE_1K)
                .build()
        }
        val model = Firebase.ai.generativeModel(
            modelName = "gemini-nano-banana-2.1",
            generationConfig = config
        )
        val response = withTimeout(60_000L) {
            model.generateContent(prompt)
        }
        val bitmap = response.candidates.firstOrNull()
            ?.content?.parts?.filterIsInstance<ImagePart>()
            ?.firstOrNull()?.image
            ?: throw IllegalStateException("Nano Banana returned no image data for prompt.")

        withContext(Dispatchers.IO) {
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        }
        return outFile
    }
}

class HybridVoiceAdapter(private val context: Context) : VoiceProvider {
    @OptIn(PublicPreviewAPI::class)
    override suspend fun synthesizeVoiceToWav(
        text: String,
        language: String,
        gender: VoiceGender,
        style: VoiceStyle,
        outFile: File,
        preferGeminiTts: Boolean
    ): VoiceSynthesisResult {
        outFile.parentFile?.mkdirs()
        if (preferGeminiTts) {
            try {
                val voiceName = when (gender) {
                    VoiceGender.FEMALE -> if (style == VoiceStyle.ENERGETIC) "Aoede" else "Kore"
                    VoiceGender.MALE -> if (style == VoiceStyle.AUTHORITATIVE) "Charon" else "Puck"
                }
                val ttsConfig = generationConfig {
                    responseModalities = listOf(ResponseModality.AUDIO)
                    speechConfig = SpeechConfig(Voice(voiceName))
                }
                val ttsModel = Firebase.ai.generativeModel(
                    modelName = "gemini-3.1-flash-tts-preview",
                    generationConfig = ttsConfig
                )
                val styledPrompt = "Speak in $language with a ${style.label.lowercase()} tone: $text"
                val response = withTimeout(45_000L) {
                    ttsModel.generateContent(styledPrompt)
                }
                val audioPart = response.candidates.firstOrNull()
                    ?.content?.parts?.filterIsInstance<InlineDataPart>()
                    ?.firstOrNull { it.mimeType.startsWith("audio/") }

                if (audioPart != null && audioPart.inlineData.isNotEmpty()) {
                    val bytes = audioPart.inlineData
                    val mime = audioPart.mimeType.lowercase(Locale.US)
                    withContext(Dispatchers.IO) {
                        if (mime.contains("wav")) {
                            outFile.writeBytes(bytes)
                        } else {
                            // Gemini TTS commonly returns raw 24kHz 16-bit mono PCM (audio/L16)
                            val sampleRate = Regex("rate=(\\d+)").find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24000
                            writePcmAsWav(bytes, sampleRate, 1, outFile)
                        }
                    }
                    val durationMs = estimateWavDurationMillis(outFile)
                    if (durationMs > 250L) {
                        return VoiceSynthesisResult(
                            wavFile = outFile,
                            durationMillis = durationMs,
                            providerUsed = "gemini-3.1-flash-tts-preview ($voiceName)"
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini TTS failed, falling back to Android TextToSpeech: ${e.message}")
            }
        }

        // Reliable on-device Android TextToSpeech WAV synthesis fallback
        synthesizeWithAndroidTts(text, language, gender, style, outFile)
        val durationMs = estimateWavDurationMillis(outFile)
        return VoiceSynthesisResult(
            wavFile = outFile,
            durationMillis = durationMs,
            providerUsed = "Android TextToSpeech (On-Device WAV)"
        )
    }

    private suspend fun synthesizeWithAndroidTts(
        text: String,
        language: String,
        gender: VoiceGender,
        style: VoiceStyle,
        outFile: File
    ) {
        val locale = mapLanguageToLocale(language)
        suspendCancellableCoroutine { cont ->
            var ttsRef: TextToSpeech? = null
            ttsRef = TextToSpeech(context.applicationContext) { status ->
                val engine = ttsRef
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    engine?.shutdown()
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Android TextToSpeech initialization failed ($status)."))
                    }
                    return@TextToSpeech
                }
                try {
                    engine.language = locale
                    val pitch = (style.pitchShift * if (gender == VoiceGender.MALE) 0.86f else 1.04f).coerceIn(0.6f, 1.5f)
                    engine.setPitch(pitch)
                    engine.setSpeechRate(style.speechRate.coerceIn(0.65f, 1.4f))

                    val utteranceId = "sarvcut_${UUID.randomUUID()}"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            engine.shutdown()
                            if (cont.isActive) cont.resume(Unit)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            engine.shutdown()
                            if (cont.isActive) {
                                cont.resumeWithException(IllegalStateException("Android TTS synthesis error."))
                            }
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            engine.shutdown()
                            if (cont.isActive) {
                                cont.resumeWithException(IllegalStateException("Android TTS synthesis error ($errorCode)."))
                            }
                        }
                    })
                    val params = Bundle()
                    val result = engine.synthesizeToFile(text, params, outFile, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        engine.shutdown()
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("synthesizeToFile returned error $result"))
                        }
                    }
                } catch (e: Exception) {
                    engine.shutdown()
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            cont.invokeOnCancellation {
                try {
                    ttsRef.stop()
                    ttsRef.shutdown()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun mapLanguageToLocale(language: String): Locale {
        val lower = language.trim().lowercase(Locale.US)
        return when {
            lower.contains("hindi") -> Locale.forLanguageTag("hi-IN")
            lower.contains("spanish") -> Locale.forLanguageTag("es-ES")
            lower.contains("french") -> Locale.FRENCH
            lower.contains("german") -> Locale.GERMAN
            lower.contains("japanese") -> Locale.JAPANESE
            lower.contains("korean") -> Locale.KOREAN
            lower.contains("portuguese") -> Locale.forLanguageTag("pt-BR")
            lower.contains("arabic") -> Locale.forLanguageTag("ar-SA")
            lower.contains("chinese") || lower.contains("mandarin") -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.US
        }
    }

    private fun writePcmAsWav(pcm: ByteArray, sampleRate: Int, channels: Int, outFile: File) {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcm.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // AudioFormat = 1 (PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()
        FileOutputStream(outFile).use { fos ->
            fos.write(header)
            fos.write(pcm)
        }
    }

    private fun estimateWavDurationMillis(wavFile: File): Long {
        if (!wavFile.exists() || wavFile.length() <= 44L) return 0L
        val bytes = wavFile.readBytes()
        if (bytes.size <= 44) return 0L
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = bb.getInt(28).coerceAtLeast(16000)
        val dataSize = (bytes.size - 44).coerceAtLeast(0)
        return (dataSize * 1000L) / byteRate
    }
}
