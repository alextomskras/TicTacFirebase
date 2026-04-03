package com.example.tictacfirebase.utils

/**
 * Объект с утилитами и константами для приложения
 */
object AppConstants {
    const val TAG = "TicTacFirebase"
    
    // Ключи для Bundle и Intent
    const val KEY_EMAIL = "email"
    const val KEY_OPPONENT_EMAIL = "opponent_email"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_PLAYER_SYMBOL = "player_symbol"
    
    // Значения символов игроков
    const val SYMBOL_X = "X"
    const val SYMBOL_O = "O"
    
    // ID клеток игрового поля
    object CellIds {
        const val CELL_1 = 1
        const val CELL_2 = 2
        const val CELL_3 = 3
        const val CELL_4 = 4
        const val CELL_5 = 5
        const val CELL_6 = 6
        const val CELL_7 = 7
        const val CELL_8 = 8
        const val CELL_9 = 9
    }
}

/**
 * Расширения для String
 */
fun String.splitEmail(): String = this.substringBefore("@")

/**
 * Проверка на валидный email
 */
fun String.isValidEmail(): Boolean {
    return android.util.Patterns.EMAIL_ADDRESS.matcher(this).matches()
}
