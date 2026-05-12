package com.example.autochat.token

import com.example.autochat.AppState
import com.example.autochat.remote.api.AuthApi
import com.example.autochat.remote.dto.request.RefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class TokenAuthenticator @Inject constructor(
    private val authApi: Provider<AuthApi>
) : Authenticator {

    // FIX: Dùng @Volatile + synchronized để tránh nhiều request
    // cùng refresh token đồng thời (thundering herd problem)
    @Volatile private var isRefreshing = false

    override fun authenticate(route: Route?, response: Response): Request? {
        // FIX: Chặn retry vô hạn — nếu đã retry 1 lần mà vẫn 401 thì dừng
        if (response.request.header("X-Retry") != null) return null

        // FIX: Nếu response code không phải 401, không cần refresh
        if (response.code != 401) return null

        val refreshToken = AppState.refreshToken ?: return null

        // FIX: synchronized đảm bảo chỉ 1 request refresh tại một thời điểm
        return synchronized(this) {
            // Double-check: nếu thread khác đã refresh xong, dùng token mới luôn
            val currentToken = AppState.accessToken
            if (currentToken != null &&
                response.request.header("Authorization") != "Bearer $currentToken") {
                return@synchronized response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            try {
                // runBlocking vẫn cần ở đây vì OkHttp Authenticator là blocking API,
                // nhưng wrapped trong synchronized để tránh thundering herd
                val newTokens = runBlocking {
                    authApi.get().refresh(RefreshRequest(refreshToken))
                }
                AppState.accessToken = newTokens.accessToken

                response.request.newBuilder()
                    .header("Authorization", "Bearer ${newTokens.accessToken}")
                    .header("X-Retry", "true")
                    .build()

            } catch (e: Exception) {
                // FIX: Xóa cả 2 token khi refresh thất bại
                AppState.logout()
                null
            }
        }
    }
}