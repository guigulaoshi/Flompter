package com.guigulaoshiren.floatingprompter

import android.content.Intent
import android.content.SharedPreferences // Import SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.button.MaterialButton
import android.widget.EditText
import android.widget.Toast // Import Toast for user feedback
import androidx.appcompat.app.AppCompatActivity

// Key for passing text to the service
const val PROMPT_TEXT = "PROMPT_TEXT"

// Constants for SharedPreferences
private const val PREFS_NAME = "FloatingPrompterPrefs"
private const val KEY_SAVED_PROMPT = "saved_prompt_text"

class MainActivity : AppCompatActivity() {
    private lateinit var editText: EditText
    private lateinit var sharedPreferences: SharedPreferences // Declare SharedPreferences
    private val REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editText = findViewById(R.id.edit_text)
        val button: MaterialButton = findViewById(R.id.button)
        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Load saved prompt or set default text
        loadPrompt()

        button.setOnClickListener {
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                // Save the text before starting the service
                savePrompt(text)
                checkPermissionAndStartService(text)
            } else {
                // Optional: Show a message if the text is empty
                Toast.makeText(this, getString(R.string.please_enter_some_text), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Save the current text when the activity is paused (e.g., app goes to background)
    override fun onPause() {
        super.onPause()
        savePrompt(editText.text.toString())
    }

    // Function to save the prompt text to SharedPreferences
    private fun savePrompt(text: String) {
        sharedPreferences.edit().putString(KEY_SAVED_PROMPT, text).apply()
    }

    // Function to load the prompt text from SharedPreferences
    private fun loadPrompt() {
        val savedPrompt = sharedPreferences.getString(KEY_SAVED_PROMPT, null)
        if (savedPrompt != null) {
            // If there's a saved prompt, use it
            editText.setText(savedPrompt)
        } else {
            // Otherwise, set the default text (only on first launch or if saved text was cleared)
            val defaultText = getString(R.string.default_prompt_text)
            editText.setText(defaultText)
        }
    }

    // Checks permission and starts the service if granted, otherwise requests permission
    private fun checkPermissionAndStartService(text: String) {
        if (hasOverlayPermission()) {
            startFloatingService(text)
            // Decide if you still want to finish MainActivity here.
            // If the user can return via an "Edit" button, maybe don't finish?
            // finish() // Keep or remove based on desired flow
        } else {
            requestOverlayPermission()
        }
    }

    // Starts the FloatingWindowService
    private fun startFloatingService(text: String) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            putExtra(PROMPT_TEXT, text)
        }
        startService(intent)
        // Decide if you still want to finish MainActivity here.
        // If the user can return via an "Edit" button, maybe don't finish?
        finish() // Keep or remove based on desired flow
    }


    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            // Check if permission was granted *after* returning from settings
            if (Settings.canDrawOverlays(this)) {
                // Permission granted, proceed with starting the service
                // Get the latest text from EditText (user might have changed it before granting)
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    // Save again just to be absolutely sure (optional, should be saved already)
                    savePrompt(text)
                    startFloatingService(text)
                }
            } else {
                // Permission denied, show a message to the user
                Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }
}