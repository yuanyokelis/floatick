# 首版为自用调试包，未启用混淆。
# 若后续开启 minify，Room / Hilt / DataStore 的类需保留，规则由对应库自动提供。
-keepattributes *Annotation*
-dontwarn kotlinx.coroutines.**
