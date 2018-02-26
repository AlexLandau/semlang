package net.semlang.internal.test

fun isRunningInCircle(): Boolean {
    val value = System.getenv("CIRCLECI")
    return value != null && value.trim().length > 0
}
