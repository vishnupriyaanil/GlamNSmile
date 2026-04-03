package com.q8ind.glamnsmile

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions

class PromptRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {

    fun fetchAllPrompts(
        onResult: (Result<Map<String, String>>) -> Unit,
    ) {
        ensureAuthenticated { authResult ->
            authResult.fold(
                onSuccess = {
                    firestore.collection(COLLECTION_NAME)
                        .get()
                        .addOnSuccessListener { snapshot ->
                            val prompts = buildMap<String, String> {
                                snapshot.documents.forEach { doc ->
                                    put(doc.id, doc.getString(FIELD_PROMPT).orEmpty().trim())
                                }
                            }
                            onResult(Result.success(prompts))
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

    fun upsertPrompt(
        provider: AnalysisProvider,
        mode: AnalysisMode,
        prompt: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        ensureAuthenticated { authResult ->
            authResult.fold(
                onSuccess = {
                    val now = System.currentTimeMillis()
                    val documentId = "${provider.id}_${mode.id}"
                    val docRef = firestore.collection(COLLECTION_NAME).document(documentId)

                    firestore.runTransaction { transaction ->
                        val snapshot = transaction.get(docRef)
                        val createdAt = snapshot.getLong(FIELD_CREATED_AT) ?: now
                        transaction.set(
                            docRef,
                            mapOf(
                                FIELD_PROVIDER_ID to provider.id,
                                FIELD_PROVIDER_LABEL to provider.displayName,
                                FIELD_MODE_ID to mode.id,
                                FIELD_MODE_LABEL to mode.displayLabel,
                                FIELD_PROMPT to prompt,
                                FIELD_CREATED_AT to createdAt,
                                FIELD_UPDATED_AT to now,
                            ),
                            SetOptions.merge(),
                        )
                        Unit
                    }
                        .addOnSuccessListener { onResult(Result.success(Unit)) }
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
                    "Firestore access is blocked. Enable Anonymous sign-in in Firebase Authentication and allow authenticated access to $COLLECTION_NAME in Firestore rules."
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

    companion object {
        private const val COLLECTION_NAME = "analysis_prompts"
        private const val FIELD_PROVIDER_ID = "providerId"
        private const val FIELD_PROVIDER_LABEL = "providerLabel"
        private const val FIELD_MODE_ID = "modeId"
        private const val FIELD_MODE_LABEL = "modeLabel"
        private const val FIELD_PROMPT = "prompt"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"
    }
}
