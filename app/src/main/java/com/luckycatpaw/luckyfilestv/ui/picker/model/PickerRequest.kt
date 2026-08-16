package com.luckycatpaw.luckyfilestv.ui.picker.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.util.MimeTypes

data class PickerRequest(
    val mode: PickerMode,
    val allowMultiple: Boolean,
    val acceptedMimeTypes: List<String>,
    val createMimeType: String,
    val suggestedFileName: String,
    val initialUri: Uri?,
    val localOnly: Boolean,
    val openableOnly: Boolean,
    val excludeSelf: Boolean,
    val prompt: String?,
    val callerGrantFlags: Int = 0
) {
    companion object {
        const val GRANT_MASK = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        fun fromIntent(context: Context, intent: Intent): PickerRequest? {
            val mode = PickerMode.fromIntent(intent) ?: return null

            val allowMultiple =
                (mode == PickerMode.OPEN_DOCUMENT || mode == PickerMode.GET_CONTENT) &&
                    intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

            val extraMimeTypes = intent
                .getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                ?.mapNotNull(MimeTypes::normalize)
                .orEmpty()

            val baseMimeType = MimeTypes.normalize(intent.type) ?: MimeTypes.ANY

            val acceptedMimeTypes = if (extraMimeTypes.isNotEmpty()) {
                extraMimeTypes.distinct()
            } else {
                listOf(baseMimeType)
            }

            val createMimeType = if (mode == PickerMode.CREATE_DOCUMENT) {
                acceptedMimeTypes.firstOrNull { it != MimeTypes.ANY }
                    ?: MimeTypes.BINARY
            } else {
                MimeTypes.BINARY
            }

            val suggestedFileName = intent
                .getStringExtra(Intent.EXTRA_TITLE)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.new_file_default)

            val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI) as? Uri
            }

            return PickerRequest(
                mode = mode,
                allowMultiple = allowMultiple,
                acceptedMimeTypes = acceptedMimeTypes,
                createMimeType = createMimeType,
                suggestedFileName = suggestedFileName,
                initialUri = initialUri,
                localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false),
                openableOnly = intent.hasCategory(Intent.CATEGORY_OPENABLE),
                excludeSelf = intent.getBooleanExtra(DocumentsContract.EXTRA_EXCLUDE_SELF, false),
                prompt = intent.getStringExtra(DocumentsContract.EXTRA_PROMPT)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                callerGrantFlags = intent.flags and GRANT_MASK
            )
        }
    }
}
