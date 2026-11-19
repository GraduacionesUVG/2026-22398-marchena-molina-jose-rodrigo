package com.example.smartuse_frontend.e2e

/** Talks to the live backend (the debug build's SMARTUSE_BASE_URL). Only `run_test_lab.py --live` runs it. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Live
