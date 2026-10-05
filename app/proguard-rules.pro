# Правила R8 для release-сборки EISWM.
#
# Имена классов и методов не переименовываются: приложение маленькое, а с исходными именами
# и номерами строк стек ошибок из logcat машины читается без mapping.txt.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
