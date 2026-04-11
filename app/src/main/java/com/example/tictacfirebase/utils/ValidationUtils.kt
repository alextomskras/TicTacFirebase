package com.example.tictacfirebase.utils

import android.util.Patterns

/**
 * Утилиты для валидации пользовательских данных
 */
object ValidationUtils {

    /**
     * Минимальная длина пароля
     */
    private const val MIN_PASSWORD_LENGTH = 8

    /**
     * Проверка корректности email
     * @param email строка для проверки
     * @return true если email валиден
     */
    fun isValidEmail(email: String?): Boolean {
        if (email.isNullOrBlank()) return false
        return Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    /**
     * Проверка сложности пароля
     * Требования:
     * - Минимум 8 символов
     * - Хотя бы одна буква
     * - Хотя бы одна цифра
     * @param password строка для проверки
     * @return true если пароль соответствует требованиям
     */
    fun isValidPassword(password: String?): Boolean {
        if (password.isNullOrBlank()) return false
        if (password.length < MIN_PASSWORD_LENGTH) return false
        
        var hasLetter = false
        var hasDigit = false
        
        for (char in password) {
            when {
                char.isLetter() -> hasLetter = true
                char.isDigit() -> hasDigit = true
            }
        }
        
        return hasLetter && hasDigit
    }

    /**
     * Проверка username
     * Требования:
     * - Не пустой
     * - Длина от 3 до 30 символов
     * - Только буквы, цифры, подчеркивания и дефисы
     * @param username строка для проверки
     * @return true если username валиден
     */
    fun isValidUsername(username: String?): Boolean {
        if (username.isNullOrBlank()) return false
        if (username.length !in 3..30) return false
        
        // Разрешаем только буквы, цифры, подчеркивания и дефисы
        val validPattern = Regex("^[a-zA-Z0-9_-]+$")
        return validPattern.matches(username)
    }

    /**
     * Санитизация строки для безопасного хранения
     * Удаляет потенциально опасные символы
     * @param input строка для санитизации
     * @return очищенная строка
     */
    fun sanitizeInput(input: String?): String {
        if (input.isNullOrBlank()) return ""
        
        return input.trim()
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
    }

    /**
     * Сообщение об ошибке для email
     */
    fun getEmailErrorMessage(email: String?): String {
        return when {
            email.isNullOrBlank() -> "Email не может быть пустым"
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> "Некорректный формат email"
            else -> ""
        }
    }

    /**
     * Сообщение об ошибке для пароля
     */
    fun getPasswordErrorMessage(password: String?): String {
        return when {
            password.isNullOrBlank() -> "Пароль не может быть пустым"
            password.length < MIN_PASSWORD_LENGTH -> "Пароль должен содержать минимум $MIN_PASSWORD_LENGTH символов"
            !password.any { it.isLetter() } || !password.any { it.isDigit() } -> 
                "Пароль должен содержать хотя бы одну букву и одну цифру"
            else -> ""
        }
    }

    /**
     * Сообщение об ошибке для username
     */
    fun getUsernameErrorMessage(username: String?): String {
        return when {
            username.isNullOrBlank() -> "Имя пользователя не может быть пустым"
            username.length !in 3..30 -> "Имя должно содержать от 3 до 30 символов"
            !Regex("^[a-zA-Z0-9_-]+$").matches(username) -> 
                "Имя может содержать только буквы, цифры, подчеркивания и дефисы"
            else -> ""
        }
    }
}
