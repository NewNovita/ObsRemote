# Reglas de ProGuard/R8 para ObsRemote.
# OkHttp usa reflexion interna en algunas plataformas; en Android no hace falta nada especial,
# pero conservamos las anotaciones que usa para evitar warnings en builds de release.
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes *Annotation*
-keepattributes Signature
