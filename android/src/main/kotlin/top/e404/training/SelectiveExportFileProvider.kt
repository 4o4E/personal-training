package top.e404.training

import androidx.core.content.FileProvider

/** Separate manifest component to keep selective-export URI grants narrowly scoped. */
class SelectiveExportFileProvider : FileProvider()
