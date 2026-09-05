package com.goviet.keyboard.engine

/**
 * VietnameseInputEngine — typealias for VietnameseComposer.
 *
 * All composition logic, preferences, and macro management live in VietnameseComposer.
 * This typealias preserves backward compatibility for callers that reference
 * VietnameseInputEngine by name (Service, Controller, Tests).
 */
typealias VietnameseInputEngine = VietnameseComposer
