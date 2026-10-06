plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktfmt)
}

/**
 * PyCode 终端模块
 *
 * 里面是 Termux 的终端模拟器/终端 View 源码（com.termux.terminal / com.termux.view，GPL-3.0），
 * 但**会话层已经被我们改写**：TerminalSession 只走 socket（连到 Termux 里的「终端桥」），
 * 彻底删掉了原版的本地 pty / JNI / 原生库依赖 —— 所以本模块不需要 NDK。
 */
android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
