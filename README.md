# TicTacFirebase - Крестики-нолики с Firebase

Android-приложение игры "Крестики-нолики" с онлайн-функционалом, построенное на Firebase.

## 📋 Описание

Приложение представляет собой реализацию классической игры "Крестики-нолики" с возможностью:
- Регистрации и авторизации пользователей
- Онлайн-матчей с другими игроками
- Push-уведомлений о ходах и событиях игры

## 🔧 Технологии

- **Язык**: Kotlin 1.9.0
- **Минимальная версия Android**: API 21 (Android 5.0 Lollipop)
- **Целевая версия Android**: API 34 (Android 14)
- **Compile SDK**: 34
- **Backend**: Firebase
  - Firebase Authentication - авторизация пользователей
  - Firebase Realtime Database - синхронизация игрового состояния
  - Firebase Storage - хранение файлов
  - Firebase Cloud Messaging - push-уведомления

## 🛠 Инструменты сборки

- **Android Gradle Plugin**: 8.1.0
- **JDK**: 17
- **Java Compatibility**: VERSION_17

## 📦 Зависимости

- AndroidX Core KTX 1.12.0
- AndroidX AppCompat 1.6.1
- Material Design 1.10.0
- ConstraintLayout 2.1.4
- Kotlin Coroutines 1.7.3
- Picasso 2.8 - загрузка изображений
- CircleImageView 3.1.0 - круглые аватарки
- Firebase BOM 32.7.0
  - Firebase Auth
  - Firebase Storage
  - Firebase Realtime Database
  - Firebase Cloud Messaging

## 🚀 Сборка и запуск

### Требования
- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17
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

Или используйте Android Studio для запуска на эмуляторе/устройстве с Android 14.

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

## 🔐 Permissions

Приложение запрашивает следующие разрешения:
- `INTERNET` - доступ к сети
- `ACCESS_NETWORK_STATE` - проверка состояния сети
- `WAKE_LOCK` - предотвращение перехода в спящий режим
- `VIBRATE` - вибрация при уведомлениях
- `POST_NOTIFICATIONS` - отправка push-уведомлений (требуется для Android 13+)

## 📄 Лицензия

Этот проект доступен без ограничений лицензии.

## 👥 Авторы

Разработано как учебный проект по работе с Firebase и Android.

## ⚙️ Конфигурация

### Версии SDK
- **minSdk**: 21 (Android 5.0 Lollipop)
- **targetSdk**: 34 (Android 14)
- **compileSdk**: 34

### Совместимость Java
- Source Compatibility: Java 17
- Target Compatibility: Java 17

---

**Примечание**: Проект полностью совместим с Android 14 и использует современные версии библиотек и инструментов сборки.
