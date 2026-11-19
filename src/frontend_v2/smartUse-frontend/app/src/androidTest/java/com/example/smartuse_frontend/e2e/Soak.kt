package com.example.smartuse_frontend.e2e

/** Waits for WorkManager's own periodic runs (20-30 min). Emulator only; testlab/run_test_lab.py excludes it. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Soak
