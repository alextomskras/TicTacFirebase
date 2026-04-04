# Настройка Firebase Cloud Functions для отправки FCM уведомлений

## Проблема
Клиентское Android-приложение не может напрямую отправлять FCM уведомления на токены других устройств, потому что:
- Требуется авторизация через сервисный аккаунт Google
- HTTP v1 API требует OAuth 2.0 токен с правами service account
- Это небезопасно хранить ключи в клиентском приложении

## Решение: Firebase Cloud Functions

### 1. Инициализация Cloud Functions

```bash
npm install -g firebase-tools
firebase login
firebase init functions
```

Выберите TypeScript или JavaScript при инициализации.

### 2. Код Cloud Function (functions/src/index.ts)

```typescript
import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

admin.initializeApp();

export const sendGameRequestNotification = functions.database
  .ref('/fcm_queue/{pushId}')
  .onCreate(async (snapshot, context) => {
    const data = snapshot.val();
    
    if (!data?.token || !data?.title || !data?.body) {
      console.log("Invalid FCM data");
      return null;
    }

    const message = {
      notification: {
        title: data.title,
        body: data.body,
      },
      data: {
        type: "game_request",
        fromUser: data.fromUser,
        toUser: data.toUser,
      },
      token: data.token,
    };

    try {
      await admin.messaging().send(message);
      console.log("Successfully sent FCM notification");
      
      // Очищаем запись после успешной отправки
      await snapshot.ref.remove();
      return null;
    } catch (error) {
      console.error("Error sending FCM:", error);
      throw error;
    }
  });
```

### 3. Развертывание

```bash
cd functions
npm install
cd ..
firebase deploy --only functions:sendGameRequestNotification
```

### 4. Как это работает

1. Пользователь A нажимает "Request" → `sendGameRequest()` сохраняет данные в БД:
   - `/users/{userId}/request/` ← запрос на игру
   - `/fcm_queue/{pushId}` ← данные для FCM

2. Cloud Function срабатывает на создание записи в `/fcm_queue/{pushId}`

3. Cloud Function отправляет FCM уведомление через Admin SDK

4. Получатель получает уведомление через `MyFirebaseMessagingService.onMessageReceived()`

5. Приложение получателя видит запрос в БД и показывает кнопку "Accept"

## Альтернатива без Cloud Functions

Если Cloud Functions недоступны, можно использовать упрощенный вариант:
- Отправка уведомлений только когда приложение активно (через Realtime Database listeners)
- Пользователь видит запрос в UI сразу при открытии приложения
- Без push-уведомлений в фоновом режиме

В этом случае удалите код сохранения в `fcm_queue` и оставьте только запись в `request`.
