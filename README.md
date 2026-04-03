# TicTacFirebase - Крестики-нолики с Firebase

Android-приложение игры "Крестики-нолики" с онлайн-функционалом, построенное на Firebase.

## 📋 Описание

Приложение представляет собой реализацию классической игры "Крестики-нолики" с возможностью:
- Регистрации и авторизации пользователей
- Онлайн-матчей с другими игроками
- Push-уведомлений о ходах и событиях игры

## 🔧 Технологии

- **Язык**: Kotlin
- **Минимальная версия Android**: API 21 (Android 5.0 Lollipop)
- **Целевая версия Android**: API 28 (Android 9.0 Pie)
- **Backend**: Firebase
  - Firebase Authentication - авторизация пользователей
  - Firebase Realtime Database - синхронизация игрового состояния
  - Firebase Storage - хранение файлов
  - Firebase Cloud Messaging - push-уведомления

## 📦 Зависимости

- AndroidX AppCompat
- ConstraintLayout
- Kotlin Coroutines
- Picasso - загрузка изображений
- CircleImageView - круглые аватарки

## 🚀 Сборка и запуск

### Требования
- Android Studio 4.1+
- JDK 8+
- Настроенный проект Firebase

### Шаги

1. Клонируйте репозиторий:
```bash
git clone <repository-url>
cd <project-directory>
```

2. Откройте проект в Android Studio

3. Настройте Firebase:
   - Создайте проект в [Firebase Console](https://console.firebase.google.com/)
   - Добавьте Android-приложение с package name: `com.example.tictacfirebase`
   - Скачайте `google-services.json` и поместите его в папку `app/`
   - Включите необходимые сервисы Firebase:
     - Authentication (Email/Password)
     - Realtime Database
     - Storage
     - Cloud Messaging

4. Соберите и запустите приложение:
```bash
./gradlew assembleDebug
```

Или используйте Android Studio для запуска на эмуляторе/устройстве.

## 📱 Структура приложения

```
app/
├── src/main/
│   ├── java/com/example/tictacfirebase/
│   │   ├── MainActivity.kt      # Основной экран игры
│   │   ├── LoginActivity.kt     # Экран входа
│   │   ├── registerActivity.kt  # Экран регистрации
│   │   ├── models/              # Модели данных
│   │   └── service/             # Firebase сервисы
│   └── AndroidManifest.xml
└── build.gradle
```

## 🔐Permissions

Приложение запрашивает следующие разрешения:
- `INTERNET` - доступ к сети
- `ACCESS_NETWORK_STATE` - проверка состояния сети
- `WAKE_LOCK` - предотвращение перехода в спящий режим
- `VIBRATE` - вибрация при уведомлениях

## 📄 Лицензия

Этот проект доступен без ограничений лицензии.

## 👥 Авторы

Разработано как учебный проект по работе с Firebase и Android.
