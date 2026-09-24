package com.drdevrd.screenshotcleaner

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            prefs.edit().putString(KEY_API_KEY, key).apply()
            Toast.makeText(
                this,
                if (key.isEmpty()) "Key cleared" else "Key saved",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_API_KEY = "claude_api_key"

        fun getApiKey(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, "").orEmpty()
        }
    }
}
