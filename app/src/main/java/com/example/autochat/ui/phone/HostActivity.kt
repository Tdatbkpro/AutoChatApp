package com.example.autochat.ui.phone

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.appcompat.app.AppCompatActivity

class HostActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT_TEXT = "extra_result_text"
        private const val REQUEST_CODE_SPEECH = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mở voice recognition
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói câu hỏi của bạn...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            startActivityForResult(intent, REQUEST_CODE_SPEECH)
        } catch (e: Exception) {
            // Không hỗ trợ voice recognition
            val resultIntent = Intent()
            setResult(RESULT_CANCELED, resultIntent)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SPEECH) {
            val resultIntent = Intent()

            if (resultCode == RESULT_OK && data != null) {
                val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = matches?.getOrNull(0)
                resultIntent.putExtra(EXTRA_RESULT_TEXT, spokenText)
                setResult(RESULT_OK, resultIntent)
            } else {
                setResult(RESULT_CANCELED, resultIntent)
            }

            finish()
        }
    }
}