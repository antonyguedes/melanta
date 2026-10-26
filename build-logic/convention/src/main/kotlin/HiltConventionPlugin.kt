import com.melanta.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Aplicar depois do plugin Android: o plugin do Hilt exige um projeto Android já configurado. */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
        }
    }
}
