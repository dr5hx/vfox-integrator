package com.dr5hx.vfox.core

/**
 * Runs a vfox CLI invocation and returns its stdout.
 *
 * Extracted as an interface so [VfoxScanner] and [VfoxProjectResolver] can be unit-tested
 * without spawning processes.
 */
fun interface VfoxCommandRunner {
    /**
     * @param args CLI arguments after the executable, e.g. `["info", "java@21.0.2", "--json"]`.
     * @param workingDirectory directory to run in; vfox resolves version files by cwd.
     * @return stdout, or null when the command could not be run or exited non-zero.
     */
    fun run(args: List<String>, workingDirectory: String?): String?
}
