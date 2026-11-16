package io.github.aedev.flow.ui.components.shared

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.utils.ForeignLanguage
import io.github.aedev.flow.utils.TranslateIntents

/** "Translate" under a comment in another language, or under every comment where Android can't tell (#892). */
@Composable
internal fun CommentTranslateLink(
    comment: Comment,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (comment.text.isBlank() || !rememberIsForeign(comment)) return
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.translate),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier =
            modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable { TranslateIntents.open(context, comment.text) }
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
