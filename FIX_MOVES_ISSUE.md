# Исправление проблемы с ходами - игроки не могли ходить

## Проблема

Игроки не могли делать ходы в игре. После нажатия на клетку:
- Ход не отображался на доске
- Или отображался неправильный символ (email вместо X/O)
- Текущий ход не переключался корректно

## Корневые причины

### 1. Неправильное определение символов X/O в observeBoardState()

**Было:**
```kotlin
val symbol = when {
    value == player1Snapshot -> "X"
    value == player2Snapshot -> "O"
    ...
}
```

**Проблема:** Эта логика предполагала что `player1` всегда играет за "X", но в реальности:
- `firstPlayer` (кто ходит первым) должен играть за "X"
- Второй игрок должен играть за "O"
- В нашей реализации `player1 = fromUser = firstPlayer`, но код этого не учитывал явно

**Стало:**
```kotlin
// Определяем первого игрока (кто ходит первым) - он всегда X
val firstPlayer = snapshot.child("firstPlayer").value as? String ?: player1Snapshot

val symbol = when {
    value == firstPlayer -> "X"
    value == player1Snapshot && player1Snapshot != firstPlayer -> "O"
    value == player2Snapshot -> {
        if (player2Snapshot == firstPlayer) "X" else "O"
    }
    ...
}
```

Теперь символ определяется относительно `firstPlayer`, а не `player1`.

### 2. Недостаточное логирование для отладки

Добавлено подробное логирование в ключевых методах:

**sendGameRequest():**
```kotlin
Log.d("GameRepository", "Verified after sendGameRequest setup:")
Log.d("GameRepository", "  currentTurn=$verifiedCurrentTurn")
Log.d("GameRepository", "  firstPlayer=$verifiedFirstPlayer")
Log.d("GameRepository", "  player1=$verifiedPlayer1")
Log.d("GameRepository", "  player2=$verifiedPlayer2")
```

**setupGameSession():**
```kotlin
Log.d("GameRepository", "Verified after setupGameSession:")
Log.d("GameRepository", "  currentTurn=$verifiedCurrentTurn")
Log.d("GameRepository", "  firstPlayer=$verifiedFirstPlayer")
Log.d("GameRepository", "  player1=$verifiedPlayer1")
Log.d("GameRepository", "  player2=$verifiedPlayer2")
```

**observeBoardState():**
```kotlin
Log.d("GameRepository", "observeBoardState: cell $index = '$value' -> symbol '$symbol'")
```

## Изменения в файлах

### GameRepository.kt

#### 1. sendGameRequest() - улучшена настройка сессии
- Добавлены комментарии о том что `fromUser` = `firstPlayer` = "X"
- Добавлена верификация всех полей после записи
- Улучшено логирование

#### 2. setupGameSession() - явная документация
- Добавлены KDoc параметры с пояснением кто есть кто
- Добавлены комментарии в mapOf о роли каждого поля
- Добавлена верификация и логирование

#### 3. observeBoardState() - ИСПРАВЛЕНА основная проблема
- Теперь читает `firstPlayer` из БД явно
- Символ определяется относительно `firstPlayer`:
  - Если email == firstPlayer → "X"
  - Если email != firstPlayer → "O"
- Обработка пустых клеток теперь явная
- Добавлен fallback для неизвестных значений
- Улучшено логирование для отладки

## Как теперь работает игра

### 1. Отправка запроса (игрок A → игрок B)
```
sendGameRequest(fromUser=A, toUser=B):
  - Создаёт сессию с:
    * firstPlayer = A (будет ходить первым, символ X)
    * currentTurn = A (сейчас его ход)
    * player1 = A (символ X)
    * player2 = B (символ O)
```

### 2. Принятие запроса (игрок B принимает)
```
setupGameSession(player1=A, player2=B):
  - Устанавливает те же значения (для гарантии консистентности)
  - Очищает доску (клетки 1-9 = "")
```

### 3. Первый ход (игрок A ходит)
```
makeMove(sessionId, cellId=5, playerEmail=A, symbol="X"):
  - Проверяет currentTurn == A ✓
  - Проверяет клетка 5 пуста ✓
  - Записывает: клетки["5"] = "A@example.com"
  - Переключает: currentTurn = B
```

### 4. Обновление UI у обоих игроков
```
observeBoardState():
  - Читает firstPlayer = "A@example.com"
  - Для клетки 5: value = "A@example.com"
  - Так как value == firstPlayer → symbol = "X"
  - Отправляет board[4] = "X" в UI
```

## Проверка работы

### Логи которые должны появиться при успешном ходе:

**У игрока который делает ход:**
```
D/GameRepository: === MAKING MOVE ===
D/GameRepository: SessionID: userA_userB, CellId: 5, Player: userA@example.com, Symbol: X
D/GameRepository: Current turn before move: userA@example.com
D/GameRepository: Move made at cell 5 by userA@example.com (X), switched turn from userA@example.com to userB@example.com
```

**В observeBoardState (у обоих игроков):**
```
D/GameRepository: observeBoardState: player1=userA@example.com, player2=userB@example.com
D/GameRepository: observeBoardState: cell 5 = 'userA@example.com' -> symbol 'X'
D/GameRepository: observeBoardState: sending board state: ["", "", "", "", "X", "", "", "", ""]
```

**В observeCurrentTurn (у обоих игроков):**
```
D/GameRepository: observeCurrentTurn: currentTurn=userB@example.com for session=userA_userB
```

## Важные замечания

1. **firstPlayer определяет символ**: Игрок в поле `firstPlayer` ВСЕГДА играет за "X"
2. **currentTurn определяет чей ход**: Только игрок из `currentTurn` может сделать ход
3. **email сохраняется в БД**: В базе хранится email, не символ. Символ определяется клиентом
4. **Симметричный sessionId**: Имя сессии всегда сортируется alphabetically (userA_userB)

## Откат предыдущих изменений

Если предыдущие изменения вызывали проблемы, они были полностью переписаны:
- Убрана сложная проверка isMyTurn перед записью (теперь только серверная проверка)
- Упрощена логика маппинга email → символ
- Добавлена явная обработка пустых клеток
- Улучшено логирование для быстрой отладки
