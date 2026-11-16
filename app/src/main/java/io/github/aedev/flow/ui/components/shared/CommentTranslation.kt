package io.github.aedev.flow.ui.components.shared

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.translation.Translation
import io.github.aedev.flow.utils.ForeignLanguage
import kotlinx.coroutines.launch

/** A comment's translation while it shows in place of the comment's own text. */
@Stable
internal class CommentTranslationState {
    var translation by mutableStateOf<Translation?>(null)
    var loading by mutableStateOf(false)
}

@Composable
internal fun rememberCommentTranslation(comment: Comment): CommentTranslationState = remember(comment.id) { CommentTranslationState() }

/** The text to show: the translation, as plain text, while it is on; otherwise [original]. */
@Composable
internal fun CommentTranslationState.shown(original: CommentTextContent): CommentTextContent {
    val translated = translation?.text ?: return original
    return remember(translated) { CommentTextContent(AnnotatedString(translated), emptyMap()) }
}

/**
 * "Translate" under a comment in another language, or under every comment where Android can't tell;
 * once translated in place, "Show original" and the server that translated it (#892).
 */
@Composable
internal fun CommentTranslateLink(
    comment: Comment,
    color: Color,
    state: CommentTranslationState,
    modifier: Modifier = Modifier,
) {
    val translation = state.translation
    if (translation != null) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            TranslateLinkText(stringResource(R.string.translation_show_original), color) { state.translation = null }
            Text(
                text = stringResource(R.string.translation_by_host, translation.host),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }
    if (comment.text.isBlank() || !rememberIsForeign(comment)) return
    val action = rememberTranslateAction()
    val scope = rememberCoroutineScope()
    val label = stringResource(if (state.loading) R.string.translation_in_progress else R.string.translate)
    TranslateLinkText(label, color, modifier) {
        if (state.loading) return@TranslateLinkText
        scope.launch {
            state.loading = true
            try {
                state.translation = action.translate(comment.text)
            } finally {
                state.loading = false
            }
        }
    }
}

@Composable
private fun TranslateLinkText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier =
            modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
    )
}

@Composable
private fun rememberIsForeign(comment: Comment): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    val context = LocalContext.current.applicationContext
    val key = comment.id.ifBlank { comment.text }
    var foreign by remember(key) { mutableStateOf(ForeignLanguage.cached(key)) }
    if (foreign == null) {
        LaunchedEffect(key) { foreign = ForeignLanguage.isForeign(context, key, comment.text) }
    }
    return foreign == true
}
