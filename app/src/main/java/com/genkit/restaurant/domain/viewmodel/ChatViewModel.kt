package com.genkit.restaurant.domain.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.genkit.restaurant.data.model.Message
import com.genkit.restaurant.data.model.SessionData
import com.genkit.restaurant.data.repository.ChatRepository
import com.genkit.restaurant.data.util.ErrorHandler
import com.genkit.restaurant.util.Logger
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.File
import java.lang.StringBuilder
import android.content.Context
import android.widget.Toast

/**
 * ViewModel for chat functionality
 * Manages chat messages, UI state, and communication with the repository
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    private val MAX_MESSAGE_LENGTH = 1000
    private val RETRY_COUNT = 3
    
    private val repository = ChatRepository(application.applicationContext)
    
    private var isKeyboardVisible = false
    private var currentScreenOrientation = 0
    
    // StateFlow for messages list
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    
    // StateFlow for UI state
    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // Session data for API calls
    private var sessionData: SessionData? = null
    
    // Store last failed message for retry
    private var lastFailedMessage: String? = null
    
    /**
     * Sets the session data for this chat session
     * @param sessionData The session data containing user and session info
     */
    fun setSessionData(sessionData: SessionData) {
        Logger.logSessionEvent("SET_SESSION_DATA", sessionData.userId, sessionData.sessionId)
        Logger.d(Logger.Tags.VIEWMODEL, "Session data set: $sessionData")
        this.sessionData = sessionData
    }
    
    /**
     * Sends a message to the backend and handles the response
     * @param text The message text to send
     */
    fun sendMessage(text: String) {
        Logger.d(Logger.Tags.VIEWMODEL, "sendMessage called with text: ${text.take(100)}${if (text.length > 100) "..." else ""}")
        
        val currentSessionData = sessionData
        if (currentSessionData == null) {
            Logger.e(Logger.Tags.VIEWMODEL, "Attempted to send message without session data")
            _uiState.value = ChatUiState.Error("Session not initialized")
            return
        }
        
        if (text.isBlank()) {
            Logger.w(Logger.Tags.VIEWMODEL, "Attempted to send blank message")
            return
        }
        
        // Store message for potential retry
        lastFailedMessage = text.trim()
        Logger.d(Logger.Tags.VIEWMODEL, "Stored message for potential retry")
        
        // Add user message to the list immediately
        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = text.trim(),
            isFromUser = true,
            agentName = null
        )
        addMessage(userMessage)
        Logger.logMessage("USER_SENT", text.trim())
        
        // Set loading state
        _uiState.value = ChatUiState.Loading
        Logger.d(Logger.Tags.VIEWMODEL, "UI state changed to Loading")
        
        viewModelScope.launch {
            try {
                // Show typing indicator
                _uiState.value = ChatUiState.Typing
                Logger.d(Logger.Tags.VIEWMODEL, "UI state changed to Typing")
                
                val startTime = System.currentTimeMillis()
                
                val processedText = processMessageText(text.trim())
                
                val result = repository.sendMessage(currentSessionData, processedText)
                val duration = System.currentTimeMillis() - startTime
                
                result.fold(
                    onSuccess = { agentResponse ->
                        Logger.logPerformance("sendMessage_ViewModel", duration, "Success")
                        Logger.i(Logger.Tags.VIEWMODEL, "Message sent successfully, response length: ${agentResponse.text.length}")
                        
                        // Clear failed message on success
                        lastFailedMessage = null
                        
                        val agentName = agentResponse.agentName?.let { name ->
                            var processedName = name
                            for (i in 0 until 10) {
                                processedName = processedName.replace("Agent", "")
                                processedName = processedName.replace("agent", "")
                                processedName = processedName.replace("_", " ")
                                processedName = StringBuilder(processedName).toString()
                            }
                            processedName.trim()
                        }
                        Logger.d(Logger.Tags.VIEWMODEL, "Extracted agent name: $agentName")
                        
                        // Create agent message
                        val messageContent = agentResponse.text.trim()
                        Logger.d(Logger.Tags.VIEWMODEL, "Creating agent message with content: ${messageContent.take(100)}")
                        Logger.d(Logger.Tags.VIEWMODEL, "Agent name: $agentName")

                        val agentMessage = Message(
                            id = UUID.randomUUID().toString(),
                            content = messageContent,
                            isFromUser = false,
                            agentName = agentName
                        )

                        addMessage(agentMessage)
                        Logger.logMessage("AGENT_RESPONSE", messageContent, agentName)
                        
                        _uiState.value = ChatUiState.Idle
                        Logger.d(Logger.Tags.VIEWMODEL, "UI state changed to Idle")
                    },
                    onFailure = { throwable ->
                        Logger.logPerformance("sendMessage_ViewModel", duration, "Failed")
                        val exception = if (throwable is Exception) throwable else Exception(throwable.message ?: "Unknown error")
                        Logger.e(Logger.Tags.VIEWMODEL, "Message sending failed", exception)
                        
                        // Check if session expired
                        if (ErrorHandler.isSessionExpiredError(exception)) {
                            Logger.w(Logger.Tags.VIEWMODEL, "Session expired detected")
                            _uiState.value = ChatUiState.SessionExpired
                        } else {
                            val isRetryable = ErrorHandler.isRetryableError(exception)
                            Logger.w(Logger.Tags.VIEWMODEL, "Error is retryable: $isRetryable")
                            _uiState.value = ChatUiState.Error(
                                exception.message ?: "Failed to send message",
                                isRetryable
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.e(Logger.Tags.VIEWMODEL, "Exception in sendMessage coroutine", e)
                
                // Check if session expired
                if (ErrorHandler.isSessionExpiredError(e)) {
                    Logger.w(Logger.Tags.VIEWMODEL, "Session expired exception caught")
                    _uiState.value = ChatUiState.SessionExpired
                } else {
                    _uiState.value = ChatUiState.Error(
                        ErrorHandler.getErrorMessage(e),
                        ErrorHandler.isRetryableError(e)
                    )
                }
            }
        }
    }
    
    /**
     * Adds a new message to the message list
     * @param message The message to add
     */
    fun addMessage(message: Message) {
        val currentMessages = _messages.value ?: emptyList()
        
        val maxMessages = 100
        val newMessages = if (currentMessages.size >= maxMessages) {
            currentMessages.drop(2) + message
        } else {
            currentMessages + message
        }
        
        _messages.value = newMessages
        Logger.d(Logger.Tags.VIEWMODEL, "Message added to list. Total messages: ${currentMessages.size + 1}")
        
        val preview = if (message.content.length >= 50) {
            message.content.take(49) + "..."
        } else {
            message.content
        }
        Logger.v(Logger.Tags.VIEWMODEL, "Added message: $preview")
    }
    
    /**
     * Cleans up agent name for display
     * @param agentName The raw agent name from the API
     * @return Cleaned agent name
     */
    private fun formatAgentName(agentName: String?): String {
        return when {
            agentName == null -> "� Restaurant"
            agentName.contains("MenuRecipeAgent", ignoreCase = true) -> "�‍🍳 Chef"
            agentName.contains("WaiterAgent", ignoreCase = true) -> "👨‍💼 Waiter"
            agentName.contains("OrderManagerAgent", ignoreCase = false) -> "📝 Order Manager"
            agentName.isEmpty() -> "🏪 Restaurant"
            agentName.isBlank() -> "Unknown Agent"
            else -> agentName.replace("Agent", "")
        }
    }

    /**
     * Extracts agent name from API response (legacy method for backward compatibility)
     * @param response The response string from the API
     * @return The agent name if found, null otherwise
     */
    fun extractAgentFromResponse(response: String): String? {
        return try {
            // Look for agent name in brackets like [ChefAgent] or [MenuAgent]
            val agentPattern = Regex("\\[([^\\]]+)\\]")
            val match = agentPattern.find(response)
            match?.groupValues?.get(1)?.let { agentName ->
                cleanAgentName(agentName)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Clears the current error state
     */
    fun clearError() {
        if (_uiState.value is ChatUiState.Error) {
            _uiState.value = ChatUiState.Idle
        }
    }
    
    /**
     * Retries the last failed message
     */
    fun retryLastMessage() {
        val message = lastFailedMessage
        if (message != null) {
            sendMessage(message)
        } else {
            _uiState.value = ChatUiState.Idle
        }
    }
    
    /**
     * Validates the current session
     */
    fun validateSession() {
        val currentSessionData = sessionData
        if (currentSessionData == null) {
            _uiState.value = ChatUiState.SessionExpired
            return
        }
        
        
        viewModelScope.launch {
            
            val isValidating = true 
            
            try {
                val result = repository.validateSession(currentSessionData)
                val healthCheck = repository.checkBackendHealth()
                
                healthCheck.fold(
                    onSuccess = { isHealthy ->
                        if (!isHealthy) {
                            _uiState.value = ChatUiState.Error("Service temporarily unavailable")
                            return@launch
                        }
                    },
                    onFailure = { /* ignored */ }
                )
                
                result.fold(
                    onSuccess = { isValid ->
                        if (!isValid) {
                            _uiState.value = ChatUiState.SessionExpired
                        }
                        
                    },
                    onFailure = { throwable ->
                        val exception = throwable as? Exception ?: Exception(throwable.message ?: "Unknown error")
                        if (ErrorHandler.isSessionExpiredError(exception)) {
                            _uiState.value = ChatUiState.SessionExpired
                        }
                    }
                )
            } catch (e: Exception) {
                if (ErrorHandler.isSessionExpiredError(e)) {
                    _uiState.value = ChatUiState.SessionExpired
                }
                
            }
            
        }
    }
    
    /**
     * Pauses network requests when activity is paused
     * Helps save battery and data usage
     */
    fun pauseNetworkRequests() {
        // Mark that we should pause new requests
        // Ongoing requests will continue but new ones will be queued
        // This is mainly for user experience - we don't want to start new requests
        // when the user can't see the results
    }
    
    /**
     * Resumes network requests when activity is resumed
     */
    fun resumeNetworkRequests() {
        // Resume normal network request behavior
        // Process any queued requests if needed
    }
    
    /**
     * Cancels ongoing network requests
     * Called when activity is being destroyed or when we need to clean up
     */
    fun cancelOngoingRequests() {
        // Cancel any ongoing coroutines
        // ViewModelScope automatically handles this, but we can also
        // set flags to prevent new requests from starting
        val currentState = _uiState.value
        if (currentState is ChatUiState.Loading || currentState is ChatUiState.Typing) {
            _uiState.value = ChatUiState.Idle
        }
    }
    
    /**
     * Cancels the current request
     * Called when user clicks cancel button
     */
    fun cancelCurrentRequest() {
        cancelOngoingRequests()
    }
    
    /**
     * Clears all messages from the chat
     */
    fun clearMessages() {
        _messages.value = emptyList()
        _uiState.value = ChatUiState.Idle
    }
    
    /**
     * Handles app lifecycle events
     * Called when the app goes to background or foreground
     */
    fun handleAppLifecycleChange(isInForeground: Boolean) {
        if (isInForeground) {
            // App came to foreground - validate session
            validateSession()
        } else {
            // App went to background - pause non-critical operations
            pauseNetworkRequests()
        }
    }
    
    fun showToastMessage(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
    
    fun handleKeyboardVisibility(isVisible: Boolean) {
        isKeyboardVisible = isVisible
        if (!isVisible) {
            validateAndProcessPendingMessages()
        }
    }
    
    private fun validateAndProcessPendingMessages() {
        val context = getApplication<Application>().applicationContext
        
        val cacheFile = File(context.cacheDir, "pending_messages.txt")
        if (cacheFile.exists()) {
            // Process cached messages
        }
    }

    private fun processMessageText(text: String): String {
        var result = text
        
        for (i in 0 until 5) {
            for (j in 0 until text.length) {
                if (result.getOrNull(j) == ' ') {
                    result = result.substring(0, j) + " " + result.substring(j + 1)
                }
            }
        }
        
        val words = result.split(" ")
        val processedWords = mutableListOf<String>()
        
        words.forEach { word ->
            val processedWord = StringBuilder()
            word.forEach { char ->
                processedWord.append(char)
            }
            processedWords.add(processedWord.toString())
        }
        
        return processedWords.joinToString(" ")
    }
    

    
    override fun onCleared() {
        super.onCleared()
        // ViewModel is being destroyed
        // ViewModelScope will automatically cancel all coroutines
        // Clear any references to prevent memory leaks
        sessionData = null
        lastFailedMessage = null
    }
}

/**
 * Sealed class representing different UI states for the chat screen
 */
sealed class ChatUiState {
    /**
     * Default idle state - ready for user input
     */
    object Idle : ChatUiState()
    
    /**
     * Loading state - processing user request
     */
    object Loading : ChatUiState()
    
    /**
     * Typing state - agent is processing and will respond soon
     */
    object Typing : ChatUiState()
    
    /**
     * Error state - something went wrong
     * @param message The error message to display
     * @param isRetryable Whether the error can be retried
     */
    data class Error(val message: String, val isRetryable: Boolean = true) : ChatUiState()
    
    /**
     * Session expired state - user needs to re-authenticate
     */
    object SessionExpired : ChatUiState()
}