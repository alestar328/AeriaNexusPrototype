# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# El SDK de Agora usa JNI: sin estas reglas R8 elimina clases que el codigo
# nativo busca por nombre y la app crashea al crear el motor.
-keep class io.agora.** { *; }
-dontwarn io.agora.**
# El AAR de las gafas (com.bleequp.bleequplibrary) ya viene ofuscado por su
# fabricante, pero lee el JSON de las gafas con Gson POR REFLEXION sobre los
# nombres de campo de sus modelos. Si R8 los vuelve a renombrar, el listado deja
# de parsearse y el fallo solo aparece en release. Ver DEVLOG 2026-09-09.
-keep class com.bleequp.bleequplibrary.** { *; }
