package com.dr5hx.vfox

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.VfoxBundle"

/**
 * The plugin's user-visible strings.
 *
 * Kept in a bundle rather than written inline so the plugin follows the IDE's language. A language
 * pack translates the *platform's* bundles by providing files under their own paths, but it cannot
 * reach into a third-party plugin: a string compiled into a plugin's code stays English no matter
 * what language the IDE runs in. A bundle can be translated, because the platform looks for a
 * `VfoxBundle_zh_CN.properties` beside the default `VfoxBundle.properties` and picks it by the
 * IDE's selected locale — the same mechanism the platform's own strings use.
 *
 * A message with no `0}`-style parameters is returned verbatim (the platform only runs a value
 * through `MessageFormat` when it is given parameters), so an apostrophe in such a message is
 * literal and needs no escaping. A message *with* parameters is formatted, where an apostrophe
 * would begin a quoted section — so those avoid apostrophes, or double them.
 */
object VfoxBundle {

    private val bundle = DynamicBundle(VfoxBundle::class.java, BUNDLE)

    /** The message for [key], with optional `MessageFormat` parameters. */
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): @Nls String =
        bundle.getMessage(key, *params)
}
