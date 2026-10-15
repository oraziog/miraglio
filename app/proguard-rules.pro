# ---------------------------------------------------------------------------
# ONNX Runtime (riconoscimento dei bersagli)
# ---------------------------------------------------------------------------
# L'AAR di onnxruntime-android NON porta regole ProGuard proprie (a differenza
# di ML Kit, che protegge i propri metodi nativi). Il codice nativo cerca le
# classi Java per nome con FindClass, quindi lasciandole rinominare il
# collegamento JNI si romperebbe: l'errore si vedrebbe solo nella build di
# release e solo attivando il riconoscimento, quindi mai in fase di sviluppo.
-keep class ai.onnxruntime.** { *; }
