package com.example.autochat.ui.phone

import android.content.Context
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

object GoogleSignInHelper {

    // ⚠️ Thay bằng Web Client ID từ Google Console
    private const val WEB_CLIENT_ID = "370906248759-vm3d8hd65upt6udln34hmb56avpd0413.apps.googleusercontent.com"

    /**
     * Trả về Google ID Token để gửi lên backend.
     * Ném exception nếu user hủy hoặc lỗi.
     */
    suspend fun getIdToken(context: Context): String {
        val credentialManager = CredentialManager.create(context)

        // Nonce chống replay attack
        val rawNonce = UUID.randomUUID().toString()
        val nonce = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)   // Hiện tất cả tài khoản
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)             // Luôn hiện picker
            .setNonce(nonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(context, request)
        val credential = result.credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }

        throw Exception("Không lấy được Google credential")
    }
}