package com.example.tictacfirebase.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.tictacfirebase.repository.GameRepository

/**
 * Factory для создания GameViewModel с зависимостями
 */
class GameViewModelFactory(
    private val gameRepository: GameRepository,
    private val gameId: String,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            return GameViewModel(gameRepository, gameId, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
