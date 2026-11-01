// 顶层构建文件：只登记插件版本，不在这里 apply。
// 具体哪个模块用哪个插件，由各模块自己的 build.gradle.kts 决定。
plugins {
    id("com.android.application") version "9.4.1" apply false
}
