package com.q8ind.glamnsmile

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot

class LeadRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    fun upsertLead(
        name: String,
        phoneNumber: String,
        email: String,
        analysisMode: AnalysisMode,
        analysisProvider: AnalysisProvider,
        imageCount: Int,
        onResult: (Result<AnalysisLead>) -> Unit,
    ) {
        val normalizedPhone = normalizePhone(phoneNumber)
        if (normalizedPhone.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Phone number is required.")))
            return
        }

        ensureAuthenticated { authResult ->
            authResult.fold(
                onSuccess = {
                    val document = firestore.collection(COLLECTION_NAME).document(normalizedPhone)
                    firestore.runTransaction { transaction ->
                        val snapshot = transaction.get(document)
                        val now = System.currentTimeMillis()
                        val createdAt = snapshot.getLong("createdAt") ?: now
                        val existingFaceCount = snapshot.getLong("faceCount")?.toInt() ?: 0
                        val existingDentalCount = snapshot.getLong("dentalCount")?.toInt() ?: 0
                        val existingLastFaceAt = snapshot.getLong("lastFaceAt") ?: 0L
                        val existingLastDentalAt = snapshot.getLong("lastDentalAt") ?: 0L

                        val faceCount = existingFaceCount + if (analysisMode == AnalysisMode.FACIAL) 1 else 0
                        val dentalCount = existingDentalCount + if (analysisMode == AnalysisMode.DENTAL) 1 else 0
                        val lastFaceAt = if (analysisMode == AnalysisMode.FACIAL) now else existingLastFaceAt
                        val lastDentalAt = if (analysisMode == AnalysisMode.DENTAL) now else existingLastDentalAt

                        val lead = AnalysisLead(
                            id = normalizedPhone,
                            name = name.trim(),
                            phoneNumber = phoneNumber.trim(),
                            normalizedPhone = normalizedPhone,
                            email = email.trim(),
                            faceCount = faceCount,
                            dentalCount = dentalCount,
                            lastFaceAt = lastFaceAt,
                            lastDentalAt = lastDentalAt,
                            createdAt = createdAt,
                            updatedAt = now,
                        )

                        val historyRef = document.collection(HISTORY_COLLECTION).document()
                        val historyEntry = AnalysisHistoryEntry(
                            id = historyRef.id,
                            analysisModeId = analysisMode.id,
                            analysisModeLabel = analysisMode.displayLabel,
                            providerId = analysisProvider.id,
                            providerLabel = analysisProvider.displayName,
                            imageCount = imageCount,
                            createdAt = now,
                        )

                        transaction.set(document, lead.toProfileMap())
                        transaction.set(historyRef, historyEntry.toMap())
                        lead
                    }
                        .addOnSuccessListener { lead -> onResult(Result.success(lead)) }
                        .addOnFailureListener { error ->
                            onResult(Result.failure(asUserFacingError(error)))
                        }
                },
                onFailure = { error ->
                    onResult(Result.failure(asUserFacingError(error)))
                },
            )
        }
    }

    fun loadLeads(onResult: (Result<List<AnalysisLead>>) -> Unit) {
        ensureAuthenticated { authResult ->
            authResult.fold(
                onSuccess = {
                    firestore.collection(COLLECTION_NAME)
                        .orderBy("updatedAt")
                        .get()
                        .addOnSuccessListener { snapshot ->
                            val leads = snapshot.documents
                                .mapNotNull(::documentToLead)
                                .sortedByDescending(AnalysisLead::updatedAt)
                            onResult(Result.success(leads))
                        }
                        .addOnFailureListener { error ->
                            onResult(Result.failure(asUserFacingError(error)))
                        }
                },
                onFailure = { error ->
                    onResult(Result.failure(asUserFacingError(error)))
                },
            )
        }
    }

    fun loadHistory(
        normalizedPhone: String,
        onResult: (Result<List<AnalysisHistoryEntry>>) -> Unit,
    ) {
        val key = normalizePhone(normalizedPhone)
        if (key.isBlank()) {
            onResult(Result.success(emptyList()))
            return
        }

        ensureAuthenticated { authResult ->
            authResult.fold(
                onSuccess = {
                    firestore.collection(COLLECTION_NAME)
                        .document(key)
                        .collection(HISTORY_COLLECTION)
                        .orderBy("createdAt")
                        .get()
                        .addOnSuccessListener { snapshot ->
                            val entries = snapshot.documents
                                .mapNotNull(::documentToHistoryEntry)
                                .sortedByDescending(AnalysisHistoryEntry::createdAt)
                            onResult(Result.success(entries))
                        }
                        .addOnFailureListener { error ->
                            onResult(Result.failure(asUserFacingError(error)))
                        }
                },
                onFailure = { error ->
                    onResult(Result.failure(asUserFacingError(error)))
                },
            )
        }
    }

    fun normalizePhone(phoneNumber: String): String {
        return phoneNumber.filter(Char::isDigit)
    }

    private fun ensureAuthenticated(onResult: (Result<Unit>) -> Unit) {
        if (auth.currentUser != null) {
            onResult(Result.success(Unit))
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { error ->
                onResult(Result.failure(asUserFacingError(error)))
            }
    }

    private fun asUserFacingError(error: Throwable): Exception {
        val message = when (error) {
            is FirebaseAuthException ->
                "Firebase Authentication rejected anonymous sign-in. Enable Anonymous sign-in in the Firebase Authentication console for this project."

            is FirebaseFirestoreException ->
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    "Firestore access is blocked. Enable Anonymous sign-in in Firebase Authentication and allow authenticated access to analysis_leads and analysis_leads/*/history in Firestore rules."
                } else {
                    error.localizedMessage.orEmpty()
                }

            else -> error.localizedMessage.orEmpty()
        }.ifBlank {
            "Unable to connect to Firebase right now."
        }

        return if (error is Exception) {
            IllegalStateException(message, error)
        } else {
            IllegalStateException(message)
        }
    }

    private fun documentToLead(document: DocumentSnapshot): AnalysisLead? {
        val id = document.id
        val name = document.getString("name").orEmpty()
        val phoneNumber = document.getString("phoneNumber").orEmpty()
        val normalizedPhone = document.getString("normalizedPhone").orEmpty().ifBlank { id }
        val email = document.getString("email").orEmpty()
        val faceCount = document.getLong("faceCount")?.toInt() ?: 0
        val dentalCount = document.getLong("dentalCount")?.toInt() ?: 0
        val lastFaceAt = document.getLong("lastFaceAt") ?: 0L
        val lastDentalAt = document.getLong("lastDentalAt") ?: 0L
        val createdAt = document.getLong("createdAt") ?: 0L
        val updatedAt = document.getLong("updatedAt") ?: 0L

        if (normalizedPhone.isBlank()) {
            return null
        }

        return AnalysisLead(
            id = id,
            name = name,
            phoneNumber = phoneNumber,
            normalizedPhone = normalizedPhone,
            email = email,
            faceCount = faceCount,
            dentalCount = dentalCount,
            lastFaceAt = lastFaceAt,
            lastDentalAt = lastDentalAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun AnalysisLead.toProfileMap(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "phoneNumber" to phoneNumber,
            "normalizedPhone" to normalizedPhone,
            "email" to email,
            "faceCount" to faceCount,
            "dentalCount" to dentalCount,
            "lastFaceAt" to lastFaceAt,
            "lastDentalAt" to lastDentalAt,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
        )
    }

    private fun documentToHistoryEntry(document: DocumentSnapshot): AnalysisHistoryEntry? {
        val id = document.id
        val analysisModeId = document.getString("analysisModeId").orEmpty()
        val analysisModeLabel = document.getString("analysisModeLabel").orEmpty()
        val providerId = document.getString("providerId").orEmpty()
        val providerLabel = document.getString("providerLabel").orEmpty()
        val imageCount = document.getLong("imageCount")?.toInt() ?: 0
        val createdAt = document.getLong("createdAt") ?: 0L

        if (analysisModeId.isBlank() || createdAt <= 0L) {
            return null
        }

        return AnalysisHistoryEntry(
            id = id,
            analysisModeId = analysisModeId,
            analysisModeLabel = analysisModeLabel.ifBlank { analysisModeId },
            providerId = providerId.ifBlank { AnalysisProvider.GEMINI.id },
            providerLabel = providerLabel.ifBlank { AnalysisProvider.fromId(providerId).displayName },
            imageCount = imageCount,
            createdAt = createdAt,
        )
    }

    private fun AnalysisHistoryEntry.toMap(): Map<String, Any> {
        return mapOf(
            "analysisModeId" to analysisModeId,
            "analysisModeLabel" to analysisModeLabel,
            "providerId" to providerId,
            "providerLabel" to providerLabel,
            "imageCount" to imageCount,
            "createdAt" to createdAt,
        )
    }

    companion object {
        private const val COLLECTION_NAME = "analysis_leads"
        private const val HISTORY_COLLECTION = "history"
    }
}
