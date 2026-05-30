# 🤖 AI Assistant Project Context (Tool-Kit)

Welcome to the `Tool-Kit` project! This file serves as a quick reference for AI assistants and developers to understand the project architecture, tech stack, build commands, and critical gotchas.

## 📝 Project Overview
**Tool-Kit** is a multi-functional Android application focused on image processing and scanning utilities (e.g., OCR Text Recognition, Exam Paper Eraser, Photo to Electronic Document). 

## 🛠️ Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Declarative UI)
- **Machine Learning**: Google ML Kit (Offline on-device Text Recognition)
- **Build System**: Gradle (Kotlin DSL `build.gradle.kts`)

## 🏗️ Project Structure
- `app/src/main/java/com/lacknb/toolkit/core/`: Contains core interfaces (e.g., `Tool` interface) that define a standardized way to build new feature modules.
- `app/src/main/java/com/lacknb/toolkit/features/`: Contains the actual tool implementations.
  - `image/`: Contains image processing tools like `ImageToTextTool.kt`, `ExamPaperEraserTool.kt`, and `PhotoToElectronicDocTool.kt`.
- `app/src/main/java/com/lacknb/toolkit/ui/`: Shared UI components and Compose workspace containers.

## 🚀 Build Commands (Windows PowerShell)
- **Debug Build**:
  ```powershell
  .\gradlew.bat assembleDebug
  ```
- **Release Build (with ProGuard/R8)**:
  ```powershell
  .\gradlew.bat clean assembleRelease
  ```

## ⚠️ Critical Gotchas & Best Practices

### 1. High-Resolution Image OOM (Out Of Memory) Prevention
**CRITICAL**: NEVER load raw high-resolution images directly from the Camera or Photo Album using `ImageDecoder.decodeBitmap(source)` or `MediaStore.Images.Media.getBitmap()`. Modern phone cameras output massive resolutions that will immediately trigger an OOM crash when applying matrix transformations or ML Kit parsing.
- **Solution**: Always use the `getScaledBitmap(context, uri, maxDimension = 1536)` helper method to downsample the image upon loading.
- **Error Handling**: When dealing with heavy Bitmap allocations, always use `catch (e: Throwable)` instead of `catch (e: Exception)` to ensure `OutOfMemoryError` is caught and prevents sudden app termination.

### 2. ProGuard / R8 Obfuscation Crashes in Release Builds
**CRITICAL**: The Release build uses `isMinifyEnabled = true`. By default, R8 will strip or rename Coroutines internals and ML Kit classes, leading to fatal `NullPointerException` (e.g., failing to invoke `getClass()` during Coroutine resumption/cancellation) or `ClassNotFoundException`.
- **Solution**: Always update `app/proguard-rules.pro` whenever adding new libraries that rely on reflection or JNI. 
- **Current Protections**: We currently enforce `-keep` rules for Kotlin Coroutines (`kotlinx.coroutines.**`) and Google ML Kit (`com.google.mlkit.**`) in the proguard configuration.

### 3. Jetpack Compose Side-Effects
When triggering Toast messages or heavy logic from within a Compose block, ensure they are launched properly within a `CoroutineScope` (e.g., using `rememberCoroutineScope()`) or an `ActivityResultLauncher` callback to prevent blocking the Main UI thread or causing Recomposition loops.
