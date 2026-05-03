package com.example.autochat.remote.api

import com.example.autochat.domain.model.Judge0Result
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface Judge0Api {

    /**
     * Gửi code lên Judge0.
     * - [base64Encoded] = true → server nhận/trả dữ liệu dạng base64
     * - [wait]          = true → server chờ kết quả, không trả status 1/2 nữa
     */
    @POST("submissions")
    suspend fun execute(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
        @Query("base64_encoded") base64Encoded: Boolean = true,
        @Query("wait")           wait: Boolean           = true
    ): Response<Judge0Result>
}