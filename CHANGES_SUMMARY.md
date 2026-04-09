# Изменения в проекте для корректной работы кнопок Request и Accept

## Проблема, которая была решена

1. **Кнопка Request** вызывала `performFcmSendMessages()` который отправлял уведомление самому себе вместо соперника
2. Не было механизма получения токена получателя из Firebase Database
3. Непонятно было как работает связка "email -> токен" для FCM уведомлений

## Что было изменено

### 1. GameRepository.kt

Добавлена логика получения токена получателя и сохранения данных для FCM:

```kotlin
suspend fun sendGameRequest(fromUser: String, toUser: String): Result<Unit> {
    // Получаем токен получателя из БД
    val tokenSnapshot = myRef.child("users").child(splitToUser).child("newToken").get().await()
    val recipientToken = tokenSnapshot.value as? String
    
    // Сохраняем запрос в БД (для UI получателя)
    myRef.child("users").child(splitToUser).child("request").push().setValue(fromUser).await()
    
    // Сохраняем данные для Cloud Function (для FCM уведомления)
    if (recipientToken != null) {
        val fcmData = mapOf(...)
        myRef.child("fcm_queue").push().setValue(fcmData).await()
    }
}
```

**Важно:** Прямая отправка FCM с клиента невозможна без сервера. Данные сохраняются в `fcm_queue` для обработки через Firebase Cloud Functions.

### 2. MainActivity.kt

Удалена ошибочная обработка FCM в `setupIncomingRequestsListener()`:

```kotlin
// БЫЛО (неправильно):
gameViewModel.observeGameRequests(email.splitEmail()).collect { requesterEmail ->
    performFcmSendMessages() // Отправляло уведомление самому себе!
    ...
}

// СТАЛО (правильно):
gameViewModel.observeGameRequests(email.splitEmail()).collect { requesterEmail ->
    // FCM уже был отправлен отправителем запроса через Cloud Function
    // Здесь просто показываем UI
    etEmail.setText(requesterEmail)
    buAcceptEvent.isEnabled = true
    ...
}
```

## Как теперь работает процесс

### Отправка запроса (кнопка Request):

1. Пользователь A вводит email пользователя B
2. Нажимает кнопку "Request"
3. Вызывается `gameViewModel.onEvent(UiEvent.SendGameRequest)`
4. `GameRepository.sendGameRequest()`:
   - Читает токен пользователя B из `/users/{B}/newToken`
   - Сохраняет запрос в `/users/{B}/request/` (для отображения в UI)
   - Сохраняет данные в `/fcm_queue/` (для Cloud Function)

5. **Cloud Function** (нужно настроить отдельно):
   - Срабатывает на создание записи в `/fcm_queue/`
   - Отправляет FCM уведомление на токен пользователя B
   - Очищает запись из `/fcm_queue/`

6. Пользователь B получает push-уведомление (если приложение в фоне)

### Получение запроса (кнопка Accept):

1. Пользователь B открывает приложение (или получает push)
2. `setupIncomingRequestsListener()` слушает `/users/{B}/request/`
3. При появлении запроса:
   - Показывается email отправителя в EditText
   - Активируется кнопка "Accept"
   - Показывается Toast
   - Запрос очищается

4. Пользователь B нажимает "Accept"
5. Вызывается `gameViewModel.onEvent(UiEvent.AcceptGameRequest)`
6. Создается игровая сессия в `/PlayerOnline/{sessionId}/`

## Структура данных в Firebase Realtime Database

```
users/
  {userId}/
    newToken: "FCM_token_string"
    request/
      -pushId1: "userA@example.com"
      -pushId2: "userC@example.com"
    profileImageUrl: "https://..."

fcm_queue/
  -pushId1:
    fromUser: "userA@example.com"
    toUser: "userB@example.com"
    token: "FCM_token_of_B"
    title: "Запрос на игру"
    body: "userA@example.com приглашает вас сыграть..."
    timestamp: 1234567890

PlayerOnline/
  userA_userB/  # Симметричное имя (сортировка по алфавиту)
    1: "userA@example.com"  // ход в клетку 1
    2: "userB@example.com"  // ход в клетку 2
    currentTurn: "userA@example.com"
    firstPlayer: "userA@example.com"
```

## Важное изменение: симметричное имя сессии

Теперь имя сессии генерируется симметрично - email'ы сортируются по алфавиту:
- `generateSessionId("userB@example.com", "userA@example.com")` → `"userA_userB"`
- `generateSessionId("userA@example.com", "userB@example.com")` → `"userA_userB"`

Это гарантирует, что оба игрока будут использовать одно и то же имя сессии независимо от того, кто отправил запрос.

## Очистка старых веток в базе данных

**ВАЖНО:** После внедрения симметричных имен сессий, в базе данных могут остаться старые ветки с некорректными именами (например, `t4_t3` вместо `t3_t4`).

### Как очистить базу данных от старых веток:

1. **Вручную через Firebase Console:**
   - Откройте Firebase Console → Realtime Database
   - Найдите узел `PlayerOnline`
   - Удалите все ветки с некорректным порядком имен (где первый игрок alphabetically больше второго)
   - Например, удалите `t4_t3`, оставив только `t3_t4`

2. **Или через Firebase CLI:**
   ```bash
   # Экспорт данных
   firebase database:get /PlayerOnline > playeronline_backup.json
   
   # Отредактируйте файл, удалив старые ветки
   # Затем загрузите обратно
   firebase database:set /PlayerOnline playeronline_backup.json
   ```

3. **Программно (добавить временный метод в GameRepository):**
   ```kotlin
   suspend fun cleanupOldSessions(): Result<Unit> {
       return runCatchingResult {
           val snapshot = myRef.child("PlayerOnline").get().await()
           snapshot.children.forEach { child ->
               val sessionId = child.key ?: return@forEach
               val parts = sessionId.split("_")
               if (parts.size == 2 && parts[0] > parts[1]) {
                   // Это старая сессия с неправильным порядком
                   myRef.child("PlayerOnline").child(sessionId).removeValue().await()
                   Log.d("GameRepository", "Removed old session: $sessionId")
               }
           }
       }
   }
   ```

## Что нужно сделать дополнительно

### Вариант 1: Настроить Firebase Cloud Functions (рекомендуется)

Следуйте инструкции в файле `CLOUD_FUNCTION_SETUP.md`

### Вариант 2: Работать без push-уведомлений

Если Cloud Functions недоступны:
- Удалите код сохранения в `fcm_queue` из `GameRepository.kt`
- Пользователи будут видеть запросы только когда приложение активно
- Realtime Database listener обновит UI мгновенно при открытии приложения

## Проверка работы

1. Залогиньтесь под двумя разными аккаунтами на двух устройствах/эмуляторах
2. На устройстве A введите email устройства B
3. Нажмите "Request"
4. Проверьте логи:
   ```
   D/GameRepository: Found token for user XXX, will send FCM via Cloud Function
   D/GameRepository: FCM data saved to queue for Cloud Function processing
   ```
5. На устройстве B должен появиться запрос в UI и активироваться кнопка "Accept"
6. Если настроен Cloud Function - должно прийти push-уведомление

## OkHttp не используется

В проекте **НЕ используется OkHttp**. Все работает через:
- Firebase Realtime Database SDK (для хранения данных)
- Firebase Messaging SDK (для получения уведомлений)
- Firebase Cloud Functions (для отправки уведомлений - требует настройки)
