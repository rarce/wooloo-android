# Compile-time Error Prone annotations referenced by AndroidX Test.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
# Compile-time Error Prone annotation value; javax.lang.model is a JDK compiler API.
-dontwarn javax.lang.model.element.Modifier
