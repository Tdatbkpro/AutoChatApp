package com.example.autochat.domain.repository

import com.example.autochat.CodeExecutor

interface HasCodeExecutor {
    val codeExecutor: CodeExecutor
}