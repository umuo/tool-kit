# Tool-Kit 项目技术入门教程

欢迎来到 `Tool-Kit` 开发指南！本项目是一个集成了多种实用工具（如文字识别、试卷擦除、照片转电子版等）的 Android 应用。

为了帮助您快速看懂项目源码并参与开发，本文档专门整理了本项目正在使用的主流技术栈入门指引。主要包括：**Kotlin 语言**、**Android 项目结构**、以及 **Jetpack Compose (声明式 UI 框架)**。

---

## 1. 核心编程语言：Kotlin

本项目全面采用 Kotlin 编写。Kotlin 是一门现代、简洁且高度安全的编程语言，它与 Java 100% 互操作，但消除了 Java 的许多痛点。

### 1.1 变量声明
在 Kotlin 中，使用 `val`（不可变引用，类似 Java 的 `final`）和 `var`（可变引用）来声明变量，并且支持类型推断：
```kotlin
val appName = "Tool-Kit"  // 自动推断为 String，且不可被重新赋值
var clickCount = 0        // 自动推断为 Int，可以改变数值
```

### 1.2 空安全 (Null Safety)
这是 Kotlin 的一大杀器，它能在编译期消灭绝大多数的 `NullPointerException`（空指针异常）。
```kotlin
var text: String = "Hello"
// text = null // 编译报错！普通类型默认不能为 null

var nullableText: String? = "Hi"
nullableText = null // 允许为 null，因为加了 ?
```
在使用可能为空的对象时，我们通常使用安全调用符 `?.` 配合 `let`：
```kotlin
// 如果 uri 不为 null，则执行括号内的代码
tempCameraUri?.let { uri ->
    val bitmap = getScaledBitmap(context, uri)
}
```

### 1.3 扩展函数 (Extension Functions)
不用修改原来类的源码，也能给它“加上”新方法。这在处理 Android 的 Context、Bitmap 时非常方便。

### 1.4 协程 (Coroutines)
本项目在处理重型任务（如图片裁切、ML Kit 引擎文字识别）时，均使用了协程来防止界面卡顿（ANR）。
```kotlin
coroutineScope.launch {
    // 这里是在主线程（用来更新 UI）
    isProcessing = true 

    // withContext 切换到后台工作线程执行耗时任务
    val result = withContext(Dispatchers.Default) {
        processHeavyImage(bitmap)
    }

    // 处理完毕，自动切回主线程更新 UI
    isProcessing = false
}
```

---

## 2. Android 工程目录结构

当您打开 `e:\JavaProject\tool-kit\` 时，标准的 Android 工程结构通常如下：

```text
tool-kit/
 ├── app/                      # 主应用模块
 │   ├── build.gradle.kts      # app 层的构建脚本 (配置依赖、版本号、混淆规则)
 │   ├── proguard-rules.pro    # 代码混淆保护规则 (我们在这保护了协程和 ML Kit)
 │   └── src/
 │       └── main/
 │           ├── AndroidManifest.xml # 应用清单文件 (声明权限、四大组件)
 │           ├── java/com/lacknb/toolkit/
 │           │   ├── core/      # 核心接口和基类 (如 Tool 接口)
 │           │   ├── features/  # 具体的工具包 (如 image 包下的 ExamPaperEraserTool)
 │           │   └── ui/        # 全局共用 UI 页面容器 (如 ToolWorkspaceContainer)
 │           └── res/           # 资源文件 (图标、静态图片等)
 ├── build.gradle.kts          # 项目根级构建脚本
 ├── settings.gradle.kts       # 引入了哪些模块
 └── docs/                     # 文档目录 (本文档所在位置)
```

---

## 3. UI 架构：Jetpack Compose

如果您以前学习过 XML 画界面或者前端的 DOM，请在这里转换思维。本项目采用了 Google 最新的 **Jetpack Compose** 框架。

**Compose 的核心思想**：UI 是一个依赖“状态”的函数。你描述 UI 长什么样，当数据（状态）变化时，Compose 会自动重绘发生变化的部分。

### 3.1 带有 `@Composable` 注解的函数
界面完全用纯 Kotlin 函数构成，只有带有 `@Composable` 注解的函数才能调用其他 Composable 组件。
```kotlin
@Composable
fun SimpleButton() {
    Button(onClick = { /* 点击逻辑 */ }) {
        Text(text = "点我")
    }
}
```

### 3.2 常见布局组件
就像搭建乐高积木一样，我们通过组合基础组件来构建复杂的画面：
* **`Column`**：将内部元素**从上到下**垂直排列。
* **`Row`**：将内部元素**从左到右**水平排列。
* **`Box`**：像千层饼一样，将元素一层层**堆叠**在一起（比如在一个图片上方叠加文字）。

### 3.3 状态管理 (State)
在传统的 UI 里，你需要 `textView.setText("新内容")` 去主动修改界面。
在 Compose 里，你只需要修改“数据变量”，界面就会自动刷新。

```kotlin
@Composable
fun Counter() {
    // remember 保证在重组(刷新)时不丢失数据
    // mutableStateOf 包装了一个会被 Compose 监听的变量
    var count by remember { mutableStateOf(0) }

    Column {
        Text("当前点击了 $count 次")
        Button(onClick = { count++ }) {
            Text("增加")
        }
    }
}
```
*在 `ExamPaperEraserTool.kt` 等代码中，您会看到 `isProcessing` 等状态变量，只要它们被修改为 `true`，界面就会自动弹出加载框。*

### 3.4 样式修饰器：Modifier
Modifier 是 Compose 调整外观的核心武器，用于控制组件的宽高、背景色、边距、点击事件等。注意，**Modifier 的调用顺序会影响最终效果**。
```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()        // 占满宽度
        .height(280.dp)        // 高度 280dp
        .clip(RoundedCornerShape(12.dp)) // 切成圆角
        .background(Color.Black)         // 背景黑色
        .clickable { /* 点击后要做的事 */ }
) {
    // 内部组件...
}
```

---

## 4. 高级特色支持：Google ML Kit

在 `ImageToTextTool` (文字识别功能) 中，我们并没有从零手写 AI 算法，而是集成了 **Google ML Kit**。这是一个强大的移动端本地机器学习库：
* **纯离线运行**：无需网络请求，速度极快且保护隐私。
* **集成方式简便**：将图片传入后，ML Kit 会异步返回解析好的文本区块（Text Blocks）。

> **⚠️ 防坑预警**：在打 Release 包（混淆）时，这类第三方通过底层运行的库，以及协程的内部类非常容易被 R8 编译器优化掉从而引发闪退。我们在 `proguard-rules.pro` 里写上了对应的 `-keep` 规则，如果您今后引入了类似的库（如 OpenCV、Tesseract），也请千万记得加上免混淆规则！

---
*本文档通过 AI 辅助编写，希望这份指南能帮您更快地上手并掌控 Tool-Kit 项目！*
