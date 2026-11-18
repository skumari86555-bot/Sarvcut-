package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AppConfig
import com.example.data.model.ContentReport
import com.example.data.model.ResearchReport
import com.example.data.model.ResearchSource
import com.example.data.model.UsageLog
import com.example.data.model.UserProfile
import com.example.data.model.VideoProject
import com.example.data.model.VideoScene
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun <T> wrapFirestoreFlow(
        uid: String,
        path: String,
        op: OperationType,
        upstream: Flow<T>
    ): Flow<T> = upstream.catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
        throw error
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch." }
        val path = "users/$userId"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.GET,
                db.collection("users").document(userId)
                    .snapshots()
                    .map { snap ->
                        if (snap.exists()) {
                            snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        } else null
                    }
            )
        )
    }

    suspend fun ensureUserProfile(user: FirebaseUser): Result<UserProfile> {
        val docPath = "users/${user.uid}"
        return try {
            val uid = requireUserId()
            check(uid == user.uid) { "Session UID mismatch." }
            val docRef = db.collection("users").document(uid)
            val snap = docRef.get().await()
            if (snap.exists()) {
                val existing = snap.toObject(
                    UserProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existing != null) return Result.success(existing)
            }
            val safeName = (user.displayName?.takeIf { it.isNotBlank() } ?: "Sarvcut Creator").take(100)
            val safeEmail = (user.email?.takeIf { it.isNotBlank() } ?: "creator@sarvcut.app").take(200)
            val payload = mutableMapOf<String, Any>(
                "userId" to uid,
                "displayName" to safeName,
                "email" to safeEmail,
                "spendingLimitUsd" to 25.0,
                "totalSpentUsd" to 0.0,
                "totalVideosCreated" to 0L,
                "defaultAspectRatio" to "16:9",
                "defaultLanguage" to "English",
                "preferLowCostImageMode" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            user.photoUrl?.toString()?.take(500)?.takeIf { it.isNotBlank() }?.let {
                payload["photoUrl"] = it
            }
            docRef.set(payload).await()
            val created = docRef.get().await().toObject(
                UserProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: UserProfile(userId = uid, displayName = safeName, email = safeEmail)
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateUserSettings(
        userId: String,
        spendingLimitUsd: Double,
        defaultAspectRatio: String,
        defaultLanguage: String,
        preferLowCostImageMode: Boolean
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user settings update." }
            val boundedLimit = spendingLimitUsd.coerceIn(0.5, 10000.0)
            val validRatio = if (defaultAspectRatio in listOf("16:9", "9:16")) defaultAspectRatio else "16:9"
            val validLang = defaultLanguage.trim().ifBlank { "English" }.take(40)
            db.collection("users").document(userId).update(
                mapOf(
                    "spendingLimitUsd" to boundedLimit,
                    "defaultAspectRatio" to validRatio,
                    "defaultLanguage" to validLang,
                    "preferLowCostImageMode" to preferLowCostImageMode,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun incrementUserUsage(userId: String, costDeltaUsd: Double, completedVideoDelta: Long): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user usage update." }
            val updates = mutableMapOf<String, Any>(
                "totalSpentUsd" to FieldValue.increment(costDeltaUsd.coerceAtLeast(0.0)),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (completedVideoDelta > 0L) {
                updates["totalVideosCreated"] = FieldValue.increment(completedVideoDelta)
            }
            db.collection("users").document(userId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeUserProjects(userId: String): Flow<List<VideoProject>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch." }
        val path = "projects"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.LIST,
                db.collection(path)
                    .whereEqualTo("userId", uid)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(VideoProject::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            .sortedByDescending { it.createdAt?.seconds ?: 0L }
                    }
            )
        )
    }

    fun observeProject(projectId: String): Flow<VideoProject?> = flow {
        val uid = requireUserId()
        val path = "projects/$projectId"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.GET,
                db.collection("projects").document(projectId)
                    .snapshots()
                    .map { snap ->
                        if (snap.exists()) {
                            snap.toObject(VideoProject::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        } else null
                    }
            )
        )
    }

    fun observeProjectScenes(projectId: String, userId: String): Flow<List<VideoScene>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch." }
        val path = "projects/$projectId/scenes"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.LIST,
                db.collection("projects").document(projectId)
                    .collection("scenes")
                    .whereEqualTo("userId", uid)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(VideoScene::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            .sortedBy { it.sceneIndex }
                    }
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.withoutNulls(): Map<String, Any> =
        filterValues { it != null } as Map<String, Any>

    suspend fun createProject(project: VideoProject): Result<String> {
        val path = "projects/${project.id}"
        return try {
            val uid = requireUserId()
            require(project.id.isNotBlank()) { "Project ID must not be blank." }
            val payload = mapOf(
                "userId" to uid,
                "jobId" to project.jobId.take(64),
                "title" to project.title.trim().ifBlank { "Untitled Video" }.take(200),
                "topic" to project.topic.trim().take(500),
                "format" to project.format,
                "aspectRatio" to project.aspectRatio,
                "language" to project.language.take(40),
                "translatedLanguage" to project.translatedLanguage?.takeIf { it.isNotBlank() }?.take(40),
                "targetDurationSeconds" to project.targetDurationSeconds.coerceIn(30L, 1200L),
                "voiceGender" to project.voiceGender,
                "voiceStyle" to project.voiceStyle,
                "productionMode" to project.productionMode,
                "status" to project.status,
                "currentStage" to project.currentStage.take(120),
                "progressPercent" to project.progressPercent.coerceIn(0L, 100L),
                "estimatedCostUsd" to project.estimatedCostUsd.coerceIn(0.0, 1000.0),
                "actualCostUsd" to project.actualCostUsd.coerceIn(0.0, 1000.0),
                "thumbnailStyle" to project.thumbnailStyle,
                "videoFileSizeBytes" to 0L,
                "renderedDurationSeconds" to 0L,
                "sceneCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).withoutNulls()

            db.collection("projects").document(project.id).set(payload).await()
            Result.success(project.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateProjectFields(projectId: String, fields: Map<String, Any?>): Result<Unit> {
        val path = "projects/$projectId"
        return try {
            requireUserId()
            val sanitized = buildMap<String, Any> {
                fields.forEach { (k, v) -> if (v != null) put(k, v) }
                put("updatedAt", FieldValue.serverTimestamp())
            }
            db.collection("projects").document(projectId).update(sanitized).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun upsertScene(projectId: String, scene: VideoScene): Result<Unit> {
        val path = "projects/$projectId/scenes/${scene.id}"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("projects").document(projectId)
                .collection("scenes").document(scene.id)
            val exists = docRef.get().await().exists()
            val safeHeading = scene.heading.trim().ifBlank { "Scene ${scene.sceneIndex + 1}" }.take(150)
            val safeNarration = scene.narration.trim().ifBlank { "Scene narration." }.take(5000)
            val safeTranslated = scene.translatedNarration?.takeIf { it.isNotBlank() }?.take(5000)
            val safeVisual = scene.visualPrompt.trim().ifBlank { "Cinematic frame" }.take(2000)
            val safeSubtitle = scene.subtitleText.trim().take(2000)
            val safeDuration = scene.durationSeconds.coerceIn(2L, 180L)
            val safeImage = scene.localImagePath?.takeIf { it.isNotBlank() }?.take(500)
            val safeAudio = scene.localAudioPath?.takeIf { it.isNotBlank() }?.take(500)
            val safeError = scene.errorMessage?.takeIf { it.isNotBlank() }?.take(1000)

            if (exists) {
                val updates = mapOf<String, Any>(
                    "heading" to safeHeading,
                    "narration" to safeNarration,
                    "translatedNarration" to (safeTranslated ?: FieldValue.delete()),
                    "visualPrompt" to safeVisual,
                    "subtitleText" to safeSubtitle,
                    "durationSeconds" to safeDuration,
                    "status" to scene.status,
                    "localImagePath" to (safeImage ?: FieldValue.delete()),
                    "localAudioPath" to (safeAudio ?: FieldValue.delete()),
                    "errorMessage" to (safeError ?: FieldValue.delete()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "projectId" to projectId,
                    "sceneIndex" to scene.sceneIndex.coerceIn(0L, 30L),
                    "heading" to safeHeading,
                    "narration" to safeNarration,
                    "translatedNarration" to safeTranslated,
                    "visualPrompt" to safeVisual,
                    "subtitleText" to safeSubtitle,
                    "durationSeconds" to safeDuration,
                    "status" to scene.status,
                    "localImagePath" to safeImage,
                    "localAudioPath" to safeAudio,
                    "errorMessage" to safeError,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).withoutNulls()
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSceneFields(projectId: String, sceneId: String, fields: Map<String, Any?>): Result<Unit> {
        val path = "projects/$projectId/scenes/$sceneId"
        return try {
            requireUserId()
            val sanitized = buildMap<String, Any> {
                fields.forEach { (k, v) -> if (v != null) put(k, v) }
                put("updatedAt", FieldValue.serverTimestamp())
            }
            db.collection("projects").document(projectId)
                .collection("scenes").document(sceneId)
                .update(sanitized)
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> {
        val path = "projects/$projectId"
        return try {
            val uid = requireUserId()
            val scenesSnap = db.collection("projects").document(projectId)
                .collection("scenes")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            for (doc in scenesSnap.documents) {
                doc.reference.delete().await()
            }
            try {
                db.collection("research_reports").document("rep_$projectId").delete().await()
            } catch (_: Exception) {
            }
            db.collection("projects").document(projectId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    fun observeResearchReports(userId: String): Flow<List<ResearchReport>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch." }
        val path = "research_reports"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.LIST,
                db.collection(path)
                    .whereEqualTo("userId", uid)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(ResearchReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            .sortedByDescending { it.createdAt?.seconds ?: 0L }
                    }
            )
        )
    }

    suspend fun saveResearchReport(report: ResearchReport): Result<Unit> {
        val path = "research_reports/${report.id}"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("research_reports").document(report.id)
            val exists = docRef.get().await().exists()
            val sourceMaps = report.sources.take(15).map { src ->
                mapOf(
                    "title" to src.title.take(200),
                    "url" to src.url.take(500),
                    "publishedDate" to src.publishedDate.take(60),
                    "credibilityNote" to src.credibilityNote.take(300)
                )
            }
            val safeSummary = report.summary.trim().ifBlank { "Research summary." }.take(20000)
            val safeNotes = report.verificationNotes.trim().take(10000)
            val safeConflict = report.conflictingInfo?.takeIf { it.isNotBlank() }?.take(5000)

            if (exists) {
                docRef.update(
                    mapOf<String, Any>(
                        "summary" to safeSummary,
                        "verificationNotes" to safeNotes,
                        "conflictingInfo" to (safeConflict ?: FieldValue.delete()),
                        "sources" to sourceMaps,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "projectId" to report.projectId,
                    "topic" to report.topic.trim().take(500),
                    "summary" to safeSummary,
                    "verificationNotes" to safeNotes,
                    "conflictingInfo" to safeConflict,
                    "sources" to sourceMaps,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).withoutNulls()
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    fun observeUsageLogs(userId: String): Flow<List<UsageLog>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Session UID mismatch." }
        val path = "usage_logs"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.LIST,
                db.collection(path)
                    .whereEqualTo("userId", uid)
                    .snapshots()
                    .map { snap ->
                        snap.toObjects(UsageLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            .sortedByDescending { it.createdAt?.seconds ?: 0L }
                    }
            )
        )
    }

    suspend fun logUsage(log: UsageLog): Result<Unit> {
        val path = "usage_logs/${log.id}"
        return try {
            val uid = requireUserId()
            val payload = mapOf(
                "userId" to uid,
                "projectId" to log.projectId,
                "projectTitle" to log.projectTitle.ifBlank { "Sarvcut Job" }.take(200),
                "stage" to log.stage.take(80),
                "modelName" to log.modelName.take(80),
                "estimatedTokens" to log.estimatedTokens.coerceIn(0L, 10_000_000L),
                "costUsd" to (log.costUsd.takeIf { it.isFinite() }?.coerceIn(0.0, 1000.0) ?: 0.0),
                "status" to if (log.status == "FAILED") "FAILED" else "SUCCESS",
                "errorMessage" to log.errorMessage?.takeIf { it.isNotBlank() }?.take(2000),
                "createdAt" to FieldValue.serverTimestamp()
            ).withoutNulls()

            db.collection("usage_logs").document(log.id).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun submitContentReport(report: ContentReport): Result<Unit> {
        val path = "content_reports/${report.id}"
        return try {
            val uid = requireUserId()
            val payload = mapOf(
                "userId" to uid,
                "projectId" to report.projectId,
                "topic" to report.topic.trim().ifBlank { "Reported Project" }.take(500),
                "reason" to report.reason.trim().take(1000),
                "status" to "OPEN",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("content_reports").document(report.id).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    fun observeAppConfig(): Flow<AppConfig> = flow {
        val uid = requireUserId()
        val path = "app_config/global"
        emitAll(
            wrapFirestoreFlow(
                uid,
                path,
                OperationType.GET,
                db.collection("app_config").document("global")
                    .snapshots()
                    .map { snap ->
                        if (snap.exists()) {
                            snap.toObject(AppConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                                ?: AppConfig()
                        } else AppConfig()
                    }
            )
        )
    }

    // --- ADMIN OPERATIONS (Guarded by Firestore rules isAdmin()) ---

    fun observeAllUsersAdmin(): Flow<List<UserProfile>> = flow {
        val uid = requireUserId()
        emitAll(
            wrapFirestoreFlow(
                uid,
                "users",
                OperationType.LIST,
                db.collection("users").snapshots().map { snap ->
                    snap.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
            )
        )
    }

    fun observeAllProjectsAdmin(): Flow<List<VideoProject>> = flow {
        val uid = requireUserId()
        emitAll(
            wrapFirestoreFlow(
                uid,
                "projects",
                OperationType.LIST,
                db.collection("projects").snapshots().map { snap ->
                    snap.toObjects(VideoProject::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
            )
        )
    }

    fun observeAllUsageLogsAdmin(): Flow<List<UsageLog>> = flow {
        val uid = requireUserId()
        emitAll(
            wrapFirestoreFlow(
                uid,
                "usage_logs",
                OperationType.LIST,
                db.collection("usage_logs").snapshots().map { snap ->
                    snap.toObjects(UsageLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
            )
        )
    }

    fun observeAllContentReportsAdmin(): Flow<List<ContentReport>> = flow {
        val uid = requireUserId()
        emitAll(
            wrapFirestoreFlow(
                uid,
                "content_reports",
                OperationType.LIST,
                db.collection("content_reports").snapshots().map { snap ->
                    snap.toObjects(ContentReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
            )
        )
    }

    suspend fun updateAppConfigAdmin(config: AppConfig): Result<Unit> {
        val path = "app_config/global"
        return try {
            requireUserId()
            val payload = mapOf(
                "maxDurationSeconds" to config.maxDurationSeconds.coerceIn(30L, 1200L),
                "defaultSpendingLimitUsd" to config.defaultSpendingLimitUsd.coerceIn(0.5, 1000.0),
                "searchGroundingEnabled" to config.searchGroundingEnabled,
                "nanoBananaEnabled" to config.nanoBananaEnabled,
                "geminiTtsEnabled" to config.geminiTtsEnabled,
                "veoServerAdapterEnabled" to config.veoServerAdapterEnabled,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("app_config").document("global").set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun resolveContentReportAdmin(reportId: String, newStatus: String): Result<Unit> {
        val path = "content_reports/$reportId"
        return try {
            requireUserId()
            val valid = if (newStatus in listOf("OPEN", "RESOLVED", "DISMISSED")) newStatus else "RESOLVED"
            db.collection("content_reports").document(reportId).update(
                mapOf("status" to valid, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }
}
