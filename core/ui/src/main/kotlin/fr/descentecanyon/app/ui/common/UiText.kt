package fr.descentecanyon.app.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Text produced by a ViewModel and resolved by the UI, so ViewModels never need a [Context]
 * and messages follow the current locale.
 */
sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Text that is already human readable, such as an error message displayed by the site. */
    data class Verbatim(val value: String) : UiText

    /** A quantity string: [count] chooses the form, [args] fill it. */
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    fun asString(context: Context): String = when (this) {
        is Resource -> context.getString(id, *args.map { it.resolve(context) }.toTypedArray())
        is Verbatim -> value
        is Plural -> context.resources.getQuantityString(id, count, *args.map { it.resolve(context) }.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText = Plural(id, count, args.toList())
    }
}

private fun Any.resolve(context: Context): Any = if (this is UiText) asString(context) else this

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> {
        val context = LocalContext.current
        stringResource(id, *args.map { it.resolve(context) }.toTypedArray())
    }

    is UiText.Verbatim -> value

    is UiText.Plural -> {
        val context = LocalContext.current
        pluralStringResource(id, count, *args.map { it.resolve(context) }.toTypedArray())
    }
}
