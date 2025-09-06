package com.guigulaoshiren.floatingprompter

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences // Import SharedPreferences
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

// Constants for SharedPreferences (can reuse from MainActivity or define separately)
private const val PREFS_NAME = "FloatingPrompterPrefs"
private const val KEY_SAVED_SPEED_PROGRESS = "saved_speed_progress"
private const val KEY_SAVED_SIZE_PROGRESS = "saved_size_progress"
// Default progress values (adjust if your initial state is different)
private const val DEFAULT_SPEED_PROGRESS = 4 // Corresponds to initial scrollSpeed = 5f
private const val DEFAULT_SIZE_PROGRESS = 14 // Corresponds to initial fontScale = 1.4f (16 * 1.4 = 22.4sp)

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var promptTextView: TextView

    private lateinit var controlPanel: LinearLayout
    private lateinit var seekBarSpeed: Slider
    private lateinit var seekBarSize: Slider
    private lateinit var playPauseButton: MaterialButton
    private lateinit var editButton: MaterialButton
    private lateinit var closeButton: MaterialButton

    private lateinit var sharedPreferences: SharedPreferences // Declare SharedPreferences

    private var isDraggingPrompt = false
    private var isDraggingControl = false
    private var lastYPrompt = 0f
    private var lastYControl = 0f
    private var isPlaying = false
    private var scrollPosition = 0f
    private var scrollSpeed = 5f // Initial default
    private var fontScale = 1f   // Initial default (will be overridden by loaded progress)
    private var screenHeight = 0
    private var screenWidth = 0  // Add screen width tracking

    // Keep track of the scrolling thread
    private var scrollingThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize SharedPreferences here
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Avoid recreating view if already exists (e.g., service restarted)
        // Note: This check might be too simple if the service process was completely killed.
        // Proper state restoration might need more checks.
        if (!::floatingView.isInitialized || floatingView.parent == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            screenHeight = resources.displayMetrics.heightPixels
            screenWidth = resources.displayMetrics.widthPixels  // Get screen width

            // Initialize floating view
            val themedContext = ContextThemeWrapper(this, R.style.Theme_FloatingWindow)
            floatingView = LayoutInflater.from(themedContext).inflate(R.layout.floating_window, null)
            promptTextView = floatingView.findViewById(R.id.prompt_text_view)
            
            // Configure TextView for better text handling
            promptTextView.apply {
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                setTextIsSelectable(false)  // Disable text selection for better performance
                setSingleLine(false)  // Ensure multiple lines are supported
                
                // Add top padding to ensure first line is visible when scrolling starts
                val currentPadding = paddingLeft
                val rightPadding = paddingRight
                val bottomPadding = paddingBottom
                val topPadding = 32 // Add extra top padding
                setPadding(currentPadding, topPadding, rightPadding, bottomPadding)
            }
            
            controlPanel = floatingView.findViewById(R.id.control_panel)
            seekBarSpeed = floatingView.findViewById(R.id.slider_speed)
            seekBarSize = floatingView.findViewById(R.id.slider_size)
            playPauseButton = floatingView.findViewById(R.id.button_play_pause)
            editButton = floatingView.findViewById(R.id.button_edit)
            closeButton = floatingView.findViewById(R.id.button_close)

            // Load saved settings *before* setting listeners
            loadSettings()

            // Set layout parameters
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.5).toInt(), // Use 50% of screen height
                TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            layoutParams.gravity = Gravity.TOP
            layoutParams.y = 0 // Consider saving/restoring window position too?

            // Set touch events
            setTouchEvents(layoutParams)

            // Set control panel events (listeners)
            setControlPanelEvents()

            // Add view to window manager
            try {
                windowManager.addView(floatingView, layoutParams)
            } catch (e: Exception) {
                // Handle exceptions, e.g., if view is already added
                e.printStackTrace()
            }
        }


        // Update text content if provided
        intent?.getStringExtra(PROMPT_TEXT)?.let {
            promptTextView.text = it
            // Reset scroll position when text changes
            scrollPosition = 0f
            promptTextView.scrollTo(0, 0)
        }


        // Apply loaded/default text size initially
        // updateTextSize() // Called within loadSettings now


        return START_STICKY // Service restarts if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScrolling() // Ensure thread is stopped
        saveSettings() // Save settings before destroying
        if (::floatingView.isInitialized && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Function to save SeekBar progress
    private fun saveSettings() {
        if (::sharedPreferences.isInitialized && ::seekBarSpeed.isInitialized && ::seekBarSize.isInitialized) {
            sharedPreferences.edit().apply {
                putInt(KEY_SAVED_SPEED_PROGRESS, seekBarSpeed.value.toInt())
                putInt(KEY_SAVED_SIZE_PROGRESS, seekBarSize.value.toInt())
                apply()
            }
        }
    }

    // Function to load SeekBar progress and apply initial values
    private fun loadSettings() {
        if (::sharedPreferences.isInitialized && ::seekBarSpeed.isInitialized && ::seekBarSize.isInitialized) {
            val savedSpeedProgress = sharedPreferences.getInt(KEY_SAVED_SPEED_PROGRESS, DEFAULT_SPEED_PROGRESS)
            val savedSizeProgress = sharedPreferences.getInt(KEY_SAVED_SIZE_PROGRESS, DEFAULT_SIZE_PROGRESS)

            // Set SeekBar progress
            seekBarSpeed.value = savedSpeedProgress.toFloat()
            seekBarSize.value = savedSizeProgress.toFloat()

            // Manually apply the loaded progress values to internal state
            // This is crucial because programmatically setting progress doesn't trigger onProgressChanged
            scrollSpeed = (savedSpeedProgress + 1).toFloat()
            updateTextSize() // This will read seekBarSize.value and apply it
        }
    }


    private fun setTouchEvents(layoutParams: WindowManager.LayoutParams) {
        // Prompt area touch listener
        promptTextView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingPrompt = true
                    lastYPrompt = event.rawY
                    // Store the current scroll position when starting to drag
                    scrollPosition = promptTextView.scrollY.toFloat()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingPrompt) {
                        val deltaY = event.rawY - lastYPrompt
                        // Calculate max scroll more accurately
                        val maxScroll = calculateMaxScroll()
                        // Update scroll position based on the stored position
                        scrollPosition = max(0f, min(scrollPosition - deltaY, maxScroll))
                        promptTextView.scrollTo(0, scrollPosition.toInt())
                        lastYPrompt = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingPrompt = false
                }
            }
            true
        }

        // Control panel touch listener (unchanged)
        controlPanel.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingControl = true
                    lastYControl = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingControl) {
                        val deltaY = event.rawY - lastYControl
                        // Update window position and limit within screen bounds
                        // Ensure floatingView height is considered
                        val viewHeight = floatingView.height // Get current height
                        layoutParams.y = max(0, min(layoutParams.y + deltaY.toInt(), screenHeight - viewHeight))
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        lastYControl = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingControl = false
                }
            }
            true
        }
    }

    private fun setControlPanelEvents() {
        // Speed Slider
        seekBarSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { // Update speed only if changed by user interaction
                scrollSpeed = (value + 1).toFloat()
            }
        }
        seekBarSpeed.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                saveSettings() // Save when user finishes adjusting
            }
        })

        // Size Slider
        seekBarSize.addOnChangeListener { _, _, fromUser ->
            if (fromUser) { // Update size only if changed by user interaction
                updateTextSize()
            }
        }
        seekBarSize.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                updateTextSize() // Ensure final size is applied
                saveSettings() // Save when user finishes adjusting
            }
        })

        // Play/Pause Button (unchanged)
        playPauseButton.setOnClickListener {
            isPlaying = !isPlaying
            playPauseButton.text = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
            if (isPlaying) {
                startScrolling()
            } else {
                stopScrolling()
            }
        }

        // Edit Button (unchanged)
        editButton.setOnClickListener {
            val intent = Intent(this@FloatingWindowService, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) // Bring main activity to front
            startActivity(intent)
            stopSelf() // Close the floating window
        }

        // Close Button (unchanged)
        closeButton.setOnClickListener {
            stopSelf() // Stops the service, onDestroy will be called
        }
    }

    private fun startScrolling() {
        if (scrollingThread?.isAlive == true) {
            return
        }
        scrollingThread = Thread {
            while (isPlaying) {
                try {
                    // Calculate max scroll more accurately
                    val maxScroll = calculateMaxScroll()

                    // Stop scrolling if end is reached
                    if (scrollPosition >= maxScroll) {
                        // Stay at the bottom instead of resetting to top
                        scrollPosition = maxScroll
                        promptTextView.post { promptTextView.scrollTo(0, scrollPosition.toInt()) }
                        // Pause the scrolling thread when reaching the end
                        isPlaying = false
                        promptTextView.post { playPauseButton.text = getString(R.string.play) }
                        break
                    } else {
                        val delay = (150/scrollSpeed).toLong()
                        Thread.sleep(max(16, delay))
                        scrollPosition += 2f  // Reduced increment for smoother scrolling
                        promptTextView.post { promptTextView.scrollTo(0, scrollPosition.toInt()) }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
            }
        }.apply { start() }
    }

    private fun stopScrolling() {
        isPlaying = false // Explicitly set playing to false
        // Update button text on the main thread if needed (might already be handled)
        if (::playPauseButton.isInitialized && playPauseButton.text != getString(R.string.play)) {
            playPauseButton.post { playPauseButton.text = getString(R.string.play) }
        }
    }


    private fun updateTextSize() {
        // Ensure seekBarSize is initialized before accessing its progress
        if (::seekBarSize.isInitialized) {
            // Scale factor based on progress (adjust range 8-18 as needed)
            // Ensure minimum progress results in a readable size
            fontScale = (seekBarSize.value + 15) / 20f // Use float division
            val baseSizeSp = 16f // Base font size in scaled pixels (sp)
            promptTextView.textSize = baseSizeSp * fontScale
            
            // Keep original padding without adding extra bottom padding
            // This ensures controls stay right next to the text
            val currentPadding = promptTextView.paddingLeft
            val topPadding = promptTextView.paddingTop
            val rightPadding = promptTextView.paddingRight
            val bottomPadding = 16 // Use standard padding
            promptTextView.setPadding(currentPadding, topPadding, rightPadding, bottomPadding)

            // After changing text size, max scroll position might change
            // It's good practice to potentially recalculate or check scroll limits here if needed
            // val maxScroll = max(0, promptTextView.lineCount * promptTextView.lineHeight - promptTextView.height)
            // scrollPosition = min(scrollPosition, maxScroll.toFloat()) // Optional: Clamp current position
            // promptTextView.post { promptTextView.scrollTo(0, scrollPosition.toInt()) } // Apply clamping if needed
        }
    }

    // Helper method to calculate maximum scroll position
    private fun calculateMaxScroll(): Float {
        // Get the layout of the TextView
        val layout = promptTextView.layout
        if (layout == null) {
            // If layout is not ready, use a fallback calculation
            return max(0f, (promptTextView.lineCount * promptTextView.lineHeight - promptTextView.height).toFloat())
        }
        
        // Get the height of the last line
        val lastLineIndex = layout.lineCount - 1
        if (lastLineIndex < 0) return 0f
        
        val lastLineBottom = layout.getLineBottom(lastLineIndex)
        val viewHeight = promptTextView.height
        
        // Calculate extra padding proportional to font size
        // Use a larger multiplier (5x) to ensure enough space
        val extraPadding = promptTextView.textSize * 5
        
        // Calculate the maximum scroll position with proportional padding
        return max(0f, (lastLineBottom - viewHeight + extraPadding).toFloat())
    }
}
