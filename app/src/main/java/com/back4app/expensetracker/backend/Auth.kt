package com.back4app.expensetracker.backend

import com.parse.ParseAnonymousUtils
import com.parse.ParseCloud
import com.parse.ParseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin suspend wrappers around ParseUser. The session token is persisted by the SDK itself. */
object Auth {
    fun currentUser(): ParseUser? = ParseUser.getCurrentUser()

    fun isGuest(user: ParseUser? = currentUser()): Boolean = user != null && ParseAnonymousUtils.isLinked(user)

    fun displayName(user: ParseUser? = currentUser()): String = when {
        user == null -> "Signed out"
        isGuest(user) -> "Guest (anonymous user ${user.objectId})"
        else -> user.email ?: user.username ?: user.objectId
    }

    suspend fun signIn(email: String, password: String): ParseUser = withContext(Dispatchers.IO) {
        ParseUser.logIn(email.trim().lowercase(), password)
    }

    suspend fun signUp(email: String, password: String): ParseUser = withContext(Dispatchers.IO) {
        val user = ParseUser()
        user.username = email.trim().lowercase()
        user.email = email.trim().lowercase()
        user.setPassword(password)
        user.signUp()
        user
    }

    suspend fun continueAsGuest(): ParseUser = suspendCancellableCoroutine { cont ->
        ParseAnonymousUtils.logIn { user, e ->
            if (e != null) cont.resumeWithException(e) else cont.resume(user)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) { ParseUser.logOut() }

    /** Cheap round trip that proves the keys and the session work (Cloud function `ensureUser`). */
    suspend fun ensureUser(): Map<*, *>? = withContext(Dispatchers.IO) {
        ParseCloud.callFunction<Any?>("ensureUser", HashMap<String, Any>()) as? Map<*, *>
    }
}
